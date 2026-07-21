package com.example.agentweb.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UploadFileStore 走 {@link TempDir} 真实文件系统跑完整 save / sanitize / 二进制嗅探 / 清理逻辑。
 * 无 Spring 容器、无 Mock,纯 Infra 轻量集成。
 *
 * @author zhourui(V33215020)
 * @since 2026/05/26
 */
public class UploadFileStoreTest {

    private final UploadFileStore store = new UploadFileStore();

    private static byte[] textBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void save_withSessionId_writesUnderSubdir(@TempDir Path workingDir) throws IOException {
        byte[] content = textBytes("log line 1\nlog line 2\n");
        String saved = store.save(workingDir.toString(), "sess-X", "app.log", content);

        Path savedPath = Paths.get(saved);
        assertTrue(savedPath.isAbsolute());
        assertTrue(savedPath.startsWith(workingDir.resolve("upload_file").resolve("sess-X")));
        assertEquals("app.log", savedPath.getFileName().toString());
        assertArrayEqualsBytes(content, Files.readAllBytes(savedPath));
    }

    @Test
    public void save_withoutSessionId_fallsBackToFlatDir(@TempDir Path workingDir) throws IOException {
        String saved = store.save(workingDir.toString(), null, "trace.txt", textBytes("hello"));

        Path savedPath = Paths.get(saved);
        assertEquals(workingDir.resolve("upload_file"), savedPath.getParent());

        String saved2 = store.save(workingDir.toString(), "  ", "trace2.txt", textBytes("hi"));
        assertEquals(workingDir.resolve("upload_file"), Paths.get(saved2).getParent());
    }

    @Test
    public void save_clashingNames_appendsDashSuffix(@TempDir Path workingDir) throws IOException {
        String first = store.save(workingDir.toString(), "s", "same.log", textBytes("a"));
        String second = store.save(workingDir.toString(), "s", "same.log", textBytes("b"));
        String third = store.save(workingDir.toString(), "s", "same.log", textBytes("c"));

        assertNotEquals(first, second);
        assertNotEquals(second, third);
        assertTrue(second.endsWith("same-1.log"), "Expected -1 suffix: " + second);
        assertTrue(third.endsWith("same-2.log"), "Expected -2 suffix: " + third);
    }

    @Test
    public void save_pathTraversalInName_isSanitizedToBasename(@TempDir Path workingDir) throws IOException {
        String saved = store.save(workingDir.toString(), "s", "../../etc/passwd.log", textBytes("x"));

        Path savedPath = Paths.get(saved);
        // basename 取 passwd.log,落到 upload_file/s/ 下,无路径穿越
        assertEquals("passwd.log", savedPath.getFileName().toString());
        assertTrue(savedPath.startsWith(workingDir.resolve("upload_file").resolve("s")));
    }

    @Test
    public void save_windowsBackslashName_takesBasenameOnly(@TempDir Path workingDir) throws IOException {
        String saved = store.save(workingDir.toString(), "s", "C:\\temp\\foo.txt", textBytes("ok"));

        assertEquals("foo.txt", Paths.get(saved).getFileName().toString());
    }

    @Test
    public void save_dangerousChars_replacedWithUnderscore(@TempDir Path workingDir) throws IOException {
        // : * ? " < > | 全转 _
        String saved = store.save(workingDir.toString(), "s", "bad:name*.txt", textBytes("x"));

        String fname = Paths.get(saved).getFileName().toString();
        assertFalse(fname.contains(":"), fname);
        assertFalse(fname.contains("*"), fname);
        assertTrue(fname.endsWith(".txt"));
    }

    @Test
    public void save_leadingDots_strippedToBlockHiddenFiles(@TempDir Path workingDir) throws IOException {
        // ".bashrc" 前导点剥掉后变 "bashrc",无扩展名 → 扩展名校验失败
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                store.save(workingDir.toString(), "s", ".bashrc", textBytes("export X=1")));
        assertTrue(ex.getMessage().contains("文本类附件") || ex.getMessage().contains("文件名"));
    }

    @Test
    public void save_extensionNotInWhitelist_rejected(@TempDir Path workingDir) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                store.save(workingDir.toString(), "s", "evil.exe", textBytes("MZ")));
        assertTrue(ex.getMessage().contains("文本类附件"));
    }

    @Test
    public void save_noExtension_rejected(@TempDir Path workingDir) {
        assertThrows(IllegalArgumentException.class, () ->
                store.save(workingDir.toString(), "s", "noext", textBytes("hi")));
    }

    @Test
    public void save_emptyContent_rejected(@TempDir Path workingDir) {
        assertThrows(IllegalArgumentException.class, () ->
                store.save(workingDir.toString(), "s", "a.log", new byte[0]));
        assertThrows(IllegalArgumentException.class, () ->
                store.save(workingDir.toString(), "s", "a.log", null));
    }

    @Test
    public void save_overSizeLimit_rejected(@TempDir Path workingDir) {
        byte[] huge = new byte[(int) UploadFileStore.MAX_FILE_BYTES + 1];
        Arrays.fill(huge, (byte) 'A');
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                store.save(workingDir.toString(), "s", "big.log", huge));
        assertTrue(ex.getMessage().contains("5MB"));
    }

    @Test
    public void save_binaryContent_rejectedByNulSniff(@TempDir Path workingDir) {
        // 扩展名合法但前若干字节含 NUL → 触发二进制嗅探
        byte[] withNul = new byte[]{'h', 'i', 0x00, 'x', 'y'};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                store.save(workingDir.toString(), "s", "fake.log", withNul));
        assertTrue(ex.getMessage().contains("二进制"));
    }

    @Test
    public void save_workingDirEmpty_rejected() {
        assertThrows(IllegalArgumentException.class, () ->
                store.save(null, "s", "a.log", textBytes("x")));
        assertThrows(IllegalArgumentException.class, () ->
                store.save("   ", "s", "a.log", textBytes("x")));
    }

    @Test
    public void save_nullFilename_rejected(@TempDir Path workingDir) {
        assertThrows(IllegalArgumentException.class, () ->
                store.save(workingDir.toString(), "s", null, textBytes("x")));
    }

    @Test
    public void deleteSessionFiles_removesAllAndDirectory(@TempDir Path workingDir) throws IOException {
        store.save(workingDir.toString(), "purge-me", "a.log", textBytes("1"));
        store.save(workingDir.toString(), "purge-me", "b.txt", textBytes("2"));
        Path dir = workingDir.resolve("upload_file").resolve("purge-me");
        assertTrue(Files.exists(dir));
        assertEquals(2, Files.list(dir).count());

        store.deleteSessionFiles(workingDir.toString(), "purge-me");

        assertFalse(Files.exists(dir), "目录应被整体清掉");
    }

    @Test
    public void save_in_worktree_workingdir_should_lift_to_workspace_root_outside_worktrees(@TempDir Path workspaceRoot) throws IOException {
        // worktree 会话: workingDir 落在短命的 .worktrees/u-x/branch 内; 附件须上提到 workspace 根, 否则删分支即丢
        Path worktreeDir = workspaceRoot.resolve(".worktrees").resolve("u-x").resolve("branch");
        Files.createDirectories(worktreeDir);
        String sessionId = "sess-wt-1";

        String saved = store.save(worktreeDir.toString(), sessionId, "app.log", textBytes("hello"));

        Path savedPath = Paths.get(saved);
        Path expectedDir = workspaceRoot.resolve("upload_file").resolve(sessionId);
        assertTrue(savedPath.startsWith(expectedDir),
                "worktree 工作目录下的附件应上提到 workspace 根的 upload_file/<sessionId>/: " + saved);
        assertFalse(savedPath.toString().contains(".worktrees"),
                "附件不应落在短命的 .worktrees 子树内: " + saved);
        assertTrue(Files.exists(savedPath));
    }

    @Test
    public void deleteSessionFiles_in_worktree_workingdir_should_clean_lifted_location(@TempDir Path workspaceRoot) throws IOException {
        Path worktreeDir = workspaceRoot.resolve(".worktrees").resolve("u-x").resolve("branch");
        Files.createDirectories(worktreeDir);
        String sessionId = "sess-wt-del";
        store.save(worktreeDir.toString(), sessionId, "a.log", textBytes("1"));
        Path liftedDir = workspaceRoot.resolve("upload_file").resolve(sessionId);
        assertTrue(Files.exists(liftedDir), "前置: 上提后的落点应已建立");

        store.deleteSessionFiles(worktreeDir.toString(), sessionId);

        assertFalse(Files.exists(liftedDir), "删会话应能从上提后的落点清理附件");
    }

    @Test
    public void deleteSessionFiles_missingDir_isNoop(@TempDir Path workingDir) {
        // 不存在的会话目录,不应抛异常
        store.deleteSessionFiles(workingDir.toString(), "never-uploaded");
    }

    @Test
    public void deleteSessionFiles_blankSessionId_skippedToAvoidPurgingParent(@TempDir Path workingDir) throws IOException {
        // 模拟有内容的 upload_file 父目录
        Path parent = workingDir.resolve("upload_file");
        Files.createDirectories(parent);
        Path keeper = parent.resolve("keep.log");
        Files.write(keeper, textBytes("keep"));

        store.deleteSessionFiles(workingDir.toString(), null);
        store.deleteSessionFiles(workingDir.toString(), "  ");
        store.deleteSessionFiles(null, "s");
        store.deleteSessionFiles("", "s");

        assertTrue(Files.exists(keeper), "空 sessionId 不应误删父目录");
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "byte mismatch at " + i);
        }
    }
}
