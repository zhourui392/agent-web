package com.example.agentweb;

import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.config.FsProperties;
import com.example.agentweb.app.UploadFileStorage;
import com.example.agentweb.app.UploadPicStorage;
import com.example.agentweb.infra.RealPathWorkspacePolicy;
import com.example.agentweb.interfaces.FsController;
import com.example.agentweb.interfaces.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link FsController}.
 *
 * <p>从 {@code @SpringBootTest} 下沉到 {@code @WebMvcTest},只起 MVC 容器,
 * 不加载 SQLite/AppService/Filter 等无关 Bean,启动从 5-10s 降到 <1s。</p>
 *
 * <p>{@code FsProperties} 通过 {@code @EnableConfigurationProperties} 显式拉入,
 * 再用 {@code @DynamicPropertySource} 把 {@code @TempDir} 注入 roots(Controller 构造期就读 roots,
 * 没法靠 {@code @MockBean} + {@code @BeforeEach} 装行为);{@code UploadPicStorage} 走 {@code @MockBean},
 * 真实文件读取走 {@code @TempDir} 触发 Controller 的扩展名 / 越界 / 大小校验。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-25
 */
@WebMvcTest(FsController.class)
@EnableConfigurationProperties(FsProperties.class)
@Import({GlobalExceptionHandler.class, RealPathWorkspacePolicy.class})
public class FsControllerTest {

    /** 受 agent.fs.roots 许可的根目录;上传校验以此为准,而非 OS 用户目录。 */
    @TempDir
    static Path fsRoot;

    @DynamicPropertySource
    static void registerFsRoot(DynamicPropertyRegistry registry) {
        registry.add("agent.fs.roots", () -> fsRoot.toString());
        // 清空测试 application.yml 里残留的 upload-roots,避免影响越界判断
        registry.add("agent.fs.upload-roots", () -> "");
    }

    @Autowired
    private MockMvc mvc;

    @MockBean
    private UploadPicStorage uploadPicStore;

    /** FsController 构造依赖,1242e3d 后新增,本切片只验图片路径,文件附件不触发。 */
    @MockBean
    private UploadFileStorage uploadFileStore;

    /** {@code @WebMvcTest} 会扫描 Filter Bean,需补齐其构造依赖以免 ApplicationContext 加载失败。 */
    @MockBean
    private AuthProperties authProperties;

    /** SessionAuthFilter 构造依赖, 扫描 Filter Bean 时需补齐。 */


    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;


    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;

    /** SessionAuthFilter 构造依赖, @WebMvcTest 扫描 Filter Bean 时需补齐 */

    /** 手动登录链路依赖, SessionAuthFilter 现在还要 manual provider + props + repo, 切片测试一并 mock。 */
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    @BeforeEach
    void stubUploadPicStore() throws Exception {
        // 默认 stub:upload-image 调用都会落到 <workingDir>/upload_pic[/<sessionId>]/<name>.png
        when(uploadPicStore.save(anyString(), ArgumentMatchers.<String>any(), any(byte[].class)))
                .thenAnswer(inv -> {
                    String workingDir = inv.getArgument(0);
                    String sessionId = inv.getArgument(1);
                    Path dir = sessionId == null || sessionId.isEmpty()
                            ? Paths.get(workingDir, "upload_pic")
                            : Paths.get(workingDir, "upload_pic", sessionId);
                    return dir.resolve("stub-" + System.nanoTime() + ".png").toString();
                });
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }

    @Test
    public void roots_should_return_configured() throws Exception {
        mvc.perform(get("/api/fs/roots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem(fsRoot.toString())));
    }

    @Test
    public void uploadImage_should_save_under_upload_pic() throws Exception {
        // 工作目录建在 agent.fs.roots 之内
        Path workDir = Files.createTempDirectory(fsRoot, "agentweb-it-");
        try {
            MockMultipartFile file = new MockMultipartFile("file", "shot.png", "image/png", pngBytes());

            mvc.perform(multipart("/api/fs/upload-image")
                            .file(file)
                            .param("path", workDir.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.path", containsString("upload_pic")))
                    .andExpect(jsonPath("$.path", containsString(".png")));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void uploadImage_with_sessionId_should_save_under_session_subdir() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "agentweb-it-");
        try {
            MockMultipartFile file = new MockMultipartFile("file", "shot.png", "image/png", pngBytes());

            String sessionId = "sess-xyz-789";
            // 显式 stub 携带 sessionId 的分支,确保返回路径包含 sessionId
            when(uploadPicStore.save(eq(workDir.toString()), eq(sessionId), any(byte[].class)))
                    .thenReturn(Paths.get(workDir.toString(), "upload_pic", sessionId, "stub.png").toString());

            mvc.perform(multipart("/api/fs/upload-image")
                            .file(file)
                            .param("path", workDir.toString())
                            .param("sessionId", sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.path", containsString("upload_pic")))
                    .andExpect(jsonPath("$.path", containsString(sessionId)))
                    .andExpect(jsonPath("$.path", containsString(".png")));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void uploadImage_outside_roots_should_be_rejected() throws Exception {
        // agent.fs.roots 之外、但仍位于 JUnit 可写临时区域的目录不允许上传
        Path outside = Files.createTempDirectory(fsRoot.getParent(), "agentweb-outside-");
        try {
            MockMultipartFile file = new MockMultipartFile("file", "shot.png", "image/png", pngBytes());

            mvc.perform(multipart("/api/fs/upload-image")
                            .file(file)
                            .param("path", outside.toString()))
                    .andExpect(status().is4xxClientError());
        } finally {
            FileSystemUtils.deleteRecursively(outside);
        }
    }

    @Test
    public void image_should_serve_png_inline() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "agentweb-it-");
        try {
            Path img = workDir.resolve("pic.png");
            Files.write(img, pngBytes());

            mvc.perform(get("/api/fs/image").param("path", img.toString()))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline"));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void image_outside_roots_should_be_rejected() throws Exception {
        Path outside = Files.createTempDirectory(fsRoot.getParent(), "agentweb-outside-");
        try {
            Path img = outside.resolve("pic.png");
            Files.write(img, pngBytes());

            mvc.perform(get("/api/fs/image").param("path", img.toString()))
                    .andExpect(status().isBadRequest());
        } finally {
            FileSystemUtils.deleteRecursively(outside);
        }
    }

    @Test
    public void image_non_image_file_should_be_rejected() throws Exception {
        // roots 内但非图片扩展名:不允许当作内联图片返回
        Path workDir = Files.createTempDirectory(fsRoot, "agentweb-it-");
        try {
            Path txt = workDir.resolve("note.txt");
            Files.write(txt, new byte[]{0x01, 0x02, 0x03});

            mvc.perform(get("/api/fs/image").param("path", txt.toString()))
                    .andExpect(status().isBadRequest());
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void uploadImage_over_1mb_should_be_rejected() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "agentweb-it-");
        try {
            // 1MB + 100 字节,开头放 PNG 魔数:无大小限制时本可通过,加限制后才被拒
            byte[] big = new byte[1024 * 1024 + 100];
            byte[] sig = pngBytes();
            System.arraycopy(sig, 0, big, 0, sig.length);
            MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);

            mvc.perform(multipart("/api/fs/upload-image")
                            .file(file)
                            .param("path", workDir.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("1MB")));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    // ============ /list ============

    @Test
    public void list_default_should_return_roots_first_entry_contents() throws Exception {
        // 不传 path:走 roots[0] 分支
        Path workDir = Files.createTempDirectory(fsRoot, "list-default-");
        try {
            Files.createDirectory(workDir.resolve("sub"));
            Files.write(workDir.resolve("file.txt"), new byte[]{1, 2, 3});

            // 默认会列 fsRoot 本身,目录里至少应见到新建的 workDir
            mvc.perform(get("/api/fs/list"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void list_with_path_should_return_dirs_files_sorted() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "list-path-");
        try {
            Files.createDirectory(workDir.resolve("inner-dir"));
            Files.write(workDir.resolve("leaf.txt"), "content".getBytes());

            mvc.perform(get("/api/fs/list").param("path", workDir.toString()))
                    .andExpect(status().isOk())
                    // 内含父链接 ".." (parent 在 fsRoot 内)
                    .andExpect(jsonPath("$[?(@.name == '..')]").exists())
                    .andExpect(jsonPath("$[?(@.name == 'inner-dir')].dir").value(hasItem(true)))
                    .andExpect(jsonPath("$[?(@.name == 'leaf.txt')].dir").value(hasItem(false)));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void list_outside_roots_should_be_rejected() throws Exception {
        Path outside = Files.createTempDirectory(fsRoot.getParent(), "agentweb-list-outside-");
        try {
            mvc.perform(get("/api/fs/list").param("path", outside.toString()))
                    .andExpect(status().is4xxClientError());
        } finally {
            FileSystemUtils.deleteRecursively(outside);
        }
    }

    @Test
    public void list_nonexistent_directory_should_be_rejected() throws Exception {
        Path ghost = fsRoot.resolve("does-not-exist-" + System.nanoTime());
        mvc.perform(get("/api/fs/list").param("path", ghost.toString()))
                .andExpect(status().is4xxClientError());
    }

    // ============ /upload (generic) ============

    @Test
    public void upload_should_write_file_under_target_dir() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "upload-");
        try {
            MockMultipartFile file = new MockMultipartFile("file", "data.txt", "text/plain",
                    "hello\nworld".getBytes());

            mvc.perform(multipart("/api/fs/upload")
                            .file(file)
                            .param("path", workDir.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.path", containsString("data.txt")));

            // 真的落盘了
            assertExists(workDir.resolve("data.txt"));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void upload_outside_allowed_roots_should_be_rejected() throws Exception {
        Path outside = Files.createTempDirectory(fsRoot.getParent(), "agentweb-upload-outside-");
        try {
            MockMultipartFile file = new MockMultipartFile("file", "x.txt", "text/plain", "x".getBytes());

            mvc.perform(multipart("/api/fs/upload")
                            .file(file)
                            .param("path", outside.toString()))
                    .andExpect(status().is4xxClientError());
        } finally {
            FileSystemUtils.deleteRecursively(outside);
        }
    }

    @Test
    public void upload_with_blank_filename_should_be_rejected() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "upload-blank-");
        try {
            MockMultipartFile file = new MockMultipartFile("file", "", "text/plain", "x".getBytes());

            mvc.perform(multipart("/api/fs/upload")
                            .file(file)
                            .param("path", workDir.toString()))
                    .andExpect(status().is4xxClientError());
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void upload_with_pathTraversalFilename_should_be_rejected_withoutWritingOutsideTarget() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "upload-traversal-");
        Path escaped = workDir.getParent().resolve("escaped-" + System.nanoTime() + ".txt");
        try {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "../" + escaped.getFileName(), "text/plain", "owned".getBytes());

            mvc.perform(multipart("/api/fs/upload")
                            .file(file)
                            .param("path", workDir.toString()))
                    .andExpect(status().isBadRequest());

            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(escaped));
        } finally {
            Files.deleteIfExists(escaped);
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void upload_should_not_overwrite_existing_file() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "upload-existing-");
        Path existing = workDir.resolve("same.txt");
        Files.write(existing, "original".getBytes());
        try {
            MockMultipartFile file = new MockMultipartFile("file", "same.txt", "text/plain", "new".getBytes());

            mvc.perform(multipart("/api/fs/upload")
                            .file(file)
                            .param("path", workDir.toString()))
                    .andExpect(status().isConflict());

            org.junit.jupiter.api.Assertions.assertEquals("original", new String(Files.readAllBytes(existing)));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void filesystemEndpoints_should_reject_symlinkEscape() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "symlink-root-");
        Path outside = Files.createTempDirectory(fsRoot.getParent(), "agentweb-symlink-outside-");
        Path outsideFile = outside.resolve("secret.txt");
        Files.write(outsideFile, "secret".getBytes());
        Path link = workDir.resolve("outside-link");
        try {
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "current filesystem does not support symbolic links");
            }

            mvc.perform(get("/api/fs/list").param("path", link.toString()))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/fs/download").param("path", link.resolve("secret.txt").toString()))
                    .andExpect(status().isBadRequest());
            mvc.perform(multipart("/api/fs/upload")
                            .file(new MockMultipartFile("file", "new.txt", "text/plain", "x".getBytes()))
                            .param("path", link.toString()))
                    .andExpect(status().isBadRequest());
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
            FileSystemUtils.deleteRecursively(outside);
        }
    }

    @Test
    public void upload_should_create_missing_target_dir() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "upload-mkdir-");
        Path nested = workDir.resolve("nested").resolve("sub");
        try {
            MockMultipartFile file = new MockMultipartFile("file", "n.txt", "text/plain", "x".getBytes());

            mvc.perform(multipart("/api/fs/upload")
                            .file(file)
                            .param("path", nested.toString()))
                    .andExpect(status().isOk());

            assertExists(nested.resolve("n.txt"));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    // ============ /upload-file ============

    @Test
    public void uploadFile_should_delegate_to_store() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "upfile-");
        try {
            // 走 UploadFileStorage mock,返回固定路径,验 Controller 透传 file.bytes/originalName/sessionId
            String realWorkDir = workDir.toRealPath().toString();
            String stubReturn = Paths.get(realWorkDir, "upload_file", "s", "app.log").toString();
            when(uploadFileStore.save(eq(realWorkDir), eq("s"), eq("app.log"), any(byte[].class)))
                    .thenReturn(stubReturn);

            MockMultipartFile file = new MockMultipartFile("file", "app.log", "text/plain",
                    "log line\n".getBytes());

            mvc.perform(multipart("/api/fs/upload-file")
                            .file(file)
                            .param("path", workDir.toString())
                            .param("sessionId", "s"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.path", containsString("app.log")));

            verify(uploadFileStore).save(eq(realWorkDir), eq("s"), eq("app.log"), any(byte[].class));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void uploadFile_outside_roots_should_be_rejected() throws Exception {
        Path outside = Files.createTempDirectory(fsRoot.getParent(), "agentweb-upfile-outside-");
        try {
            MockMultipartFile file = new MockMultipartFile("file", "x.log", "text/plain", "x".getBytes());

            mvc.perform(multipart("/api/fs/upload-file")
                            .file(file)
                            .param("path", outside.toString()))
                    .andExpect(status().is4xxClientError());
        } finally {
            FileSystemUtils.deleteRecursively(outside);
        }
    }

    @Test
    public void uploadFile_over_5mb_should_be_rejected_before_store() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "upfile-big-");
        try {
            byte[] huge = new byte[(int) (5L * 1024 * 1024 + 1)];
            MockMultipartFile file = new MockMultipartFile("file", "huge.log", "text/plain", huge);

            mvc.perform(multipart("/api/fs/upload-file")
                            .file(file)
                            .param("path", workDir.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("5MB")));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    // ============ /download ============

    @Test
    public void download_should_return_octet_stream_with_filename_header() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "dl-");
        try {
            Path file = workDir.resolve("report.txt");
            Files.write(file, "abc".getBytes());

            mvc.perform(get("/api/fs/download").param("path", file.toString()))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            containsString("attachment")))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            containsString("report.txt")));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void download_with_chinese_filename_should_urlencode() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "dl-cn-");
        try {
            Path file = workDir.resolve("中文报告.txt");
            Files.write(file, "x".getBytes());

            mvc.perform(get("/api/fs/download").param("path", file.toString()))
                    .andExpect(status().isOk())
                    // 中文文件名走 URLEncoder 后应含 %E4 等 percent-encoded 字节
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            containsString("UTF-8''")));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void download_outside_roots_should_be_rejected() throws Exception {
        Path outside = Files.createTempDirectory(fsRoot.getParent(), "agentweb-dl-outside-");
        try {
            Path file = outside.resolve("x.txt");
            Files.write(file, "x".getBytes());

            mvc.perform(get("/api/fs/download").param("path", file.toString()))
                    .andExpect(status().is4xxClientError());
        } finally {
            FileSystemUtils.deleteRecursively(outside);
        }
    }

    @Test
    public void download_nonexistent_or_directory_should_be_rejected() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "dl-bad-");
        try {
            // 目录而非文件
            mvc.perform(get("/api/fs/download").param("path", workDir.toString()))
                    .andExpect(status().is4xxClientError());
            // 不存在
            mvc.perform(get("/api/fs/download").param("path", workDir.resolve("ghost.txt").toString()))
                    .andExpect(status().is4xxClientError());
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    // ============ /delete ============

    @Test
    public void delete_existing_file_should_succeed() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "del-");
        try {
            Path file = workDir.resolve("to-delete.txt");
            Files.write(file, "x".getBytes());

            mvc.perform(delete("/api/fs/delete").param("path", file.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(file), "文件应被删除");
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void delete_directory_should_be_rejected() throws Exception {
        Path workDir = Files.createTempDirectory(fsRoot, "del-dir-");
        try {
            mvc.perform(delete("/api/fs/delete").param("path", workDir.toString()))
                    .andExpect(status().is4xxClientError());
            // 目录仍然存在
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(workDir));
        } finally {
            FileSystemUtils.deleteRecursively(workDir);
        }
    }

    @Test
    public void delete_nonexistent_should_be_rejected() throws Exception {
        Path ghost = fsRoot.resolve("never-was-" + System.nanoTime() + ".txt");
        mvc.perform(delete("/api/fs/delete").param("path", ghost.toString()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    public void delete_outside_roots_should_be_rejected() throws Exception {
        Path outside = Files.createTempDirectory(fsRoot.getParent(), "agentweb-del-outside-");
        try {
            Path file = outside.resolve("x.txt");
            Files.write(file, "x".getBytes());

            mvc.perform(delete("/api/fs/delete").param("path", file.toString()))
                    .andExpect(status().is4xxClientError());
            // 文件不被删
            org.junit.jupiter.api.Assertions.assertTrue(Files.exists(file));
        } finally {
            FileSystemUtils.deleteRecursively(outside);
        }
    }

    private static void assertExists(Path p) {
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(p), "期望存在: " + p);
    }
}
