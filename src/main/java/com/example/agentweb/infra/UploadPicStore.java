package com.example.agentweb.infra;

import com.example.agentweb.domain.worktree.WorkspaceUploadRoot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 把图片字节落盘到工作空间下的 {@code upload_pic} 子目录,供对话图片上传使用。
 *
 * <p>扩展名由字节魔数嗅探得出(不信任调用方传入的文件名),无法识别的字节直接拒绝。
 * 文件名带时间戳+随机后缀,避免剪贴板截图同名互相覆盖。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
@Component
@Slf4j
public class UploadPicStore {

    /** 工作空间下承载图片的固定子目录名。 */
    public static final String SUBDIR = "upload_pic";

    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private static final int BYTE_MASK = 0xFF;

    /** PNG 文件起始 4 字节魔数。 */
    private static final int[] PNG_SIGNATURE = {0x89, 0x50, 0x4E, 0x47};
    /** JPEG 文件起始 3 字节魔数。 */
    private static final int[] JPG_SIGNATURE = {0xFF, 0xD8, 0xFF};
    /** GIF 文件起始 4 字节魔数 (GIF8)。 */
    private static final int[] GIF_SIGNATURE = {0x47, 0x49, 0x46, 0x38};
    /** BMP 文件起始 2 字节魔数 (BM)。 */
    private static final int[] BMP_SIGNATURE = {0x42, 0x4D};
    /** WebP 容器起始 4 字节魔数 (RIFF)。 */
    private static final int[] RIFF_SIGNATURE = {0x52, 0x49, 0x46, 0x46};
    /** WebP 容器 8-11 字节标识 (WEBP)。 */
    private static final int[] WEBP_TAG = {0x57, 0x45, 0x42, 0x50};
    /** 校验 WebP 容器至少需要的字节数。 */
    private static final int WEBP_HEADER_LEN = 12;
    /** WebP 容器中 WEBP 标识的起始偏移。 */
    private static final int WEBP_TAG_OFFSET = 8;

    /**
     * 把图片字节存到 {@code <workingDir>/upload_pic/} 下(扁平结构,无 session 维度),返回落盘绝对路径。
     * 供无会话语境的调用方使用。
     *
     * @param workingDir 工作空间目录
     * @param content    图片字节,非空且能被魔数识别为支持的图片类型
     * @return 落盘文件的绝对路径
     * @throws IOException              目录创建或写入失败
     * @throws IllegalArgumentException 入参为空或字节无法识别为支持的图片类型
     */
    public String save(String workingDir, byte[] content) throws IOException {
        return save(workingDir, null, content);
    }

    /**
     * 把图片字节存到 {@code <workingDir>/upload_pic/<sessionId>/} 下,返回落盘绝对路径。
     * 按 sessionId 归集是为了支持「删除会话时一并清理图片」。
     *
     * <p>{@code sessionId} 为 null 或空时回退到扁平目录。</p>
     *
     * @param workingDir 工作空间目录
     * @param sessionId  会话 ID(可空)
     * @param content    图片字节
     * @return 落盘文件的绝对路径
     */
    public String save(String workingDir, String sessionId, byte[] content) throws IOException {
        if (workingDir == null || workingDir.trim().isEmpty()) {
            throw new IllegalArgumentException("workingDir is empty");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("image content is empty");
        }
        String ext = detectExt(content);
        if (ext == null) {
            throw new IllegalArgumentException("Unsupported image content (expect png/jpg/gif/webp/bmp)");
        }

        Path dir = resolveSaveDir(workingDir, sessionId);
        Files.createDirectories(dir);
        Path target = dir.resolve(buildFileName(ext));
        Files.write(target, content);

        String abs = target.toAbsolutePath().toString();
        log.info("upload_pic 落盘成功 path={} size={} ext={} sessionId={}",
                abs, content.length, ext, sessionId);
        return abs;
    }

    /**
     * 删除指定会话名下的图片目录 {@code <workingDir>/upload_pic/<sessionId>/}。
     *
     * <p>语义为「尽力而为」:目录不存在视为成功;空/null sessionId 直接忽略,避免误删父目录。
     * 删除失败仅记日志,不抛异常,不阻塞会话删除主流程。</p>
     */
    public void deleteSessionImages(String workingDir, String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }
        if (workingDir == null || workingDir.trim().isEmpty()) {
            return;
        }
        Path sessionDir = Paths.get(WorkspaceUploadRoot.resolve(workingDir), SUBDIR, sessionId);
        if (!Files.exists(sessionDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(sessionDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("upload_pic 删除文件失败 path={} err={}", p, e.toString());
                }
            });
            log.info("upload_pic 会话图片目录已清理 sessionId={} dir={}", sessionId, sessionDir);
        } catch (IOException e) {
            log.warn("upload_pic 遍历删除失败 sessionId={} dir={} err={}", sessionId, sessionDir, e.toString());
        }
    }

    private Path resolveSaveDir(String workingDir, String sessionId) {
        String uploadRoot = WorkspaceUploadRoot.resolve(workingDir);
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return Paths.get(uploadRoot, SUBDIR);
        }
        return Paths.get(uploadRoot, SUBDIR, sessionId);
    }

    private String buildFileName(String ext) {
        String time = LocalDateTime.now().format(NAME_TIME);
        String rand = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0x10000));
        return time + "-" + rand + "." + ext;
    }

    /**
     * 按文件头魔数判定图片扩展名。无法识别返回 {@code null}。
     */
    private String detectExt(byte[] b) {
        if (startsWith(b, PNG_SIGNATURE)) {
            return "png";
        }
        if (startsWith(b, JPG_SIGNATURE)) {
            return "jpg";
        }
        if (startsWith(b, GIF_SIGNATURE)) {
            return "gif";
        }
        if (startsWith(b, BMP_SIGNATURE)) {
            return "bmp";
        }
        // WEBP: "RIFF"...."WEBP"
        if (b.length >= WEBP_HEADER_LEN && startsWith(b, RIFF_SIGNATURE)
                && matchesAt(b, WEBP_TAG_OFFSET, WEBP_TAG)) {
            return "webp";
        }
        return null;
    }

    private boolean startsWith(byte[] b, int[] magic) {
        return matchesAt(b, 0, magic);
    }

    private boolean matchesAt(byte[] b, int offset, int[] magic) {
        if (b.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((b[offset + i] & BYTE_MASK) != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
