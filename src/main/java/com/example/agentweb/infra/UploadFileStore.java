package com.example.agentweb.infra;

import com.example.agentweb.domain.worktree.WorkspaceUploadRoot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 把聊天附件落盘到工作空间下的 {@code upload_file} 子目录,供「对话」上传文本类文件(GC 日志、堆栈、JSON 等)。
 *
 * <p>限制:
 * <ul>
 *   <li>大小上限 5 MB(由调用方在 controller 入口校验后再落到此处)</li>
 *   <li>扩展名白名单:log/txt/json/csv/md/yaml/yml/xml/properties/stacktrace/out/conf/ini</li>
 *   <li>二进制嗅探:前若干字节出现 NUL(0x00) 一律拒绝,挡掉 jar/zip/class/heap dump 等</li>
 *   <li>原文件名 sanitize,只保留 basename,杜绝 {@code ../} 路径穿越</li>
 *   <li>重名时追加 {@code -1/-2/...} 后缀,不覆盖已存在文件</li>
 * </ul>
 *
 * <p>按 sessionId 归集到 {@code upload_file/<sessionId>/},会话删除时整目录清理。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-25
 */
@Component
@Slf4j
public class UploadFileStore {

    /** 工作空间下承载附件的固定子目录名。 */
    public static final String SUBDIR = "upload_file";

    /** 单文件大小上限:5 MB。 */
    public static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;

    /** 二进制嗅探采样字节数:覆盖文本文件的常见 BOM/编码头即可。 */
    private static final int BINARY_SNIFF_BYTES = 8 * 1024;

    /** 单文件名最大重名后缀次数,防止无意义的死循环。 */
    private static final int MAX_RENAME_TRIES = 1000;

    /** 文本类扩展名白名单(小写,不含点)。 */
    private static final Set<String> ALLOWED_EXTS = new HashSet<>(Arrays.asList(
            "log", "txt", "json", "csv", "md", "yaml", "yml",
            "xml", "properties", "stacktrace", "out", "conf", "ini"
    ));

    /**
     * 把附件字节存到 {@code <workingDir>/upload_file/<sessionId>/} 下,返回落盘绝对路径。
     *
     * <p>{@code sessionId} 为 null/空 时回退到扁平目录,兼容无会话语境调用方。</p>
     *
     * @param workingDir   工作空间目录
     * @param sessionId    会话 ID(可空)
     * @param originalName 浏览器传入的原始文件名(决定扩展名与最终落盘文件名)
     * @param content      文件字节,非空且 <= 5MB
     * @return 落盘文件的绝对路径
     * @throws IOException              目录创建或写入失败
     * @throws IllegalArgumentException 入参非法、扩展名不在白名单、内容像二进制
     */
    public String save(String workingDir, String sessionId, String originalName, byte[] content) throws IOException {
        if (workingDir == null || workingDir.trim().isEmpty()) {
            throw new IllegalArgumentException("workingDir is empty");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件大小不能超过 5MB");
        }
        String safeName = sanitizeFileName(originalName);
        String ext = extOf(safeName);
        if (ext == null || !ALLOWED_EXTS.contains(ext)) {
            throw new IllegalArgumentException("仅支持文本类附件:" + ALLOWED_EXTS);
        }
        if (looksBinary(content)) {
            throw new IllegalArgumentException("检测到二进制内容,仅允许纯文本附件");
        }

        Path dir = resolveSaveDir(workingDir, sessionId);
        Files.createDirectories(dir);
        Path target = resolveNonClashingPath(dir, safeName);
        Files.write(target, content);

        String abs = target.toAbsolutePath().toString();
        log.info("upload_file 落盘成功 path={} size={} ext={} sessionId={}",
                abs, content.length, ext, sessionId);
        return abs;
    }

    /**
     * 删除指定会话名下的附件目录 {@code <workingDir>/upload_file/<sessionId>/}。
     *
     * <p>语义为「尽力而为」:目录不存在视为成功;空 sessionId 直接忽略,避免误删父目录。
     * 删除失败仅记日志,不抛异常,不阻塞会话删除主流程。</p>
     */
    public void deleteSessionFiles(String workingDir, String sessionId) {
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
                    log.warn("upload_file 删除文件失败 path={} err={}", p, e.toString());
                }
            });
            log.info("upload_file 会话附件目录已清理 sessionId={} dir={}", sessionId, sessionDir);
        } catch (IOException e) {
            log.warn("upload_file 遍历删除失败 sessionId={} dir={} err={}", sessionId, sessionDir, e.toString());
        }
    }

    private Path resolveSaveDir(String workingDir, String sessionId) {
        String uploadRoot = WorkspaceUploadRoot.resolve(workingDir);
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return Paths.get(uploadRoot, SUBDIR);
        }
        return Paths.get(uploadRoot, SUBDIR, sessionId);
    }

    /**
     * 仅保留原文件 basename:去除任何路径分隔符与控制符,丢弃前导 {@code .} 防止隐藏文件。
     * 空名一律拒绝(扩展名校验阶段也会兜底)。
     */
    private String sanitizeFileName(String original) {
        if (original == null) {
            throw new IllegalArgumentException("文件名为空");
        }
        // 浏览器可能传 "C:\\foo\\bar.log" 或 "../etc/passwd" 这种,取最后一段
        String base = original;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        // 替换危险字符:控制符、引号、空字符等
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '"' || c == '<' || c == '>' || c == '|' || c == ':' || c == '*' || c == '?') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        // 去除前导点,挡掉 .bashrc / .env 等隐藏文件名
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("文件名非法");
        }
        return cleaned;
    }

    private String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase();
    }

    /**
     * 重名时在扩展名前追加 {@code -1/-2/...} 后缀。最多 {@value #MAX_RENAME_TRIES} 次,超出抛异常。
     */
    private Path resolveNonClashingPath(Path dir, String fileName) {
        Path candidate = dir.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i <= MAX_RENAME_TRIES; i++) {
            Path next = dir.resolve(stem + "-" + i + ext);
            if (!Files.exists(next)) {
                return next;
            }
        }
        throw new IllegalStateException("同名文件过多,放弃:" + fileName);
    }

    /**
     * 朴素二进制嗅探:前 {@value #BINARY_SNIFF_BYTES} 字节出现 NUL 即视作二进制。
     * UTF-8/UTF-16 文本不会出现独立 NUL(UTF-16 文本由 BOM 标识,这里允许误判,挡得更严没坏处)。
     */
    private boolean looksBinary(byte[] content) {
        int limit = Math.min(content.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
