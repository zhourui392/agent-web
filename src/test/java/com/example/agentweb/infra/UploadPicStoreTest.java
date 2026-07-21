package com.example.agentweb.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
public class UploadPicStoreTest {

    private final UploadPicStore store = new UploadPicStore();

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02};
    }

    private static byte[] jpgBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
    }

    @Test
    public void save_png_should_land_in_upload_pic_subdir_and_return_absolute_path(@TempDir Path workingDir) throws IOException {
        byte[] content = pngBytes();
        String saved = store.save(workingDir.toString(), content);

        Path savedPath = Paths.get(saved);
        assertTrue(savedPath.isAbsolute(), "应返回绝对路径: " + saved);
        assertTrue(saved.endsWith(".png"), "png 魔数应识别为 .png 后缀: " + saved);
        assertTrue(savedPath.startsWith(workingDir.resolve(UploadPicStore.SUBDIR)),
                "应落在 upload_pic 子目录下: " + saved);
        assertArrayEquals(content, Files.readAllBytes(savedPath));
    }

    @Test
    public void save_jpeg_should_detect_as_jpg_extension(@TempDir Path workingDir) throws IOException {
        String saved = store.save(workingDir.toString(), jpgBytes());
        assertTrue(saved.endsWith(".jpg"), "jpeg 魔数应识别为 .jpg 后缀: " + saved);
    }

    @Test
    public void save_multiple_times_filenames_should_be_unique_and_not_overwrite(@TempDir Path workingDir) throws IOException {
        String first = store.save(workingDir.toString(), pngBytes());
        String second = store.save(workingDir.toString(), pngBytes());
        assertNotEquals(first, second, "两次落盘文件名应不同");
        assertTrue(Files.exists(Paths.get(first)));
        assertTrue(Files.exists(Paths.get(second)));
    }

    @Test
    public void save_non_image_bytes_throws(@TempDir Path workingDir) {
        assertThrows(IllegalArgumentException.class,
                () -> store.save(workingDir.toString(), "not an image".getBytes()));
    }

    @Test
    public void save_empty_content_throws(@TempDir Path workingDir) {
        assertThrows(IllegalArgumentException.class,
                () -> store.save(workingDir.toString(), new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> store.save(workingDir.toString(), null));
    }

    @Test
    public void save_empty_working_dir_throws() {
        assertThrows(IllegalArgumentException.class, () -> store.save("", pngBytes()));
        assertThrows(IllegalArgumentException.class, () -> store.save(null, pngBytes()));
    }

    @Test
    public void save_with_session_id_should_land_in_session_id_subdir(@TempDir Path workingDir) throws IOException {
        String sessionId = "sess-abc-123";
        String saved = store.save(workingDir.toString(), sessionId, pngBytes());

        Path savedPath = Paths.get(saved);
        Path expectedDir = workingDir.resolve(UploadPicStore.SUBDIR).resolve(sessionId);
        assertTrue(savedPath.startsWith(expectedDir),
                "应落在 upload_pic/<sessionId>/ 下: " + saved);
        assertTrue(Files.exists(savedPath));
    }

    @Test
    public void save_with_empty_session_id_should_fall_back_to_flat_dir(@TempDir Path workingDir) throws IOException {
        // 空/null sessionId 不创建子目录,与旧签名行为一致
        String saved = store.save(workingDir.toString(), null, pngBytes());
        Path savedPath = Paths.get(saved);
        assertEquals(workingDir.resolve(UploadPicStore.SUBDIR), savedPath.getParent(),
                "null sessionId 应直接落 upload_pic/ 下: " + saved);
    }

    @Test
    public void deleteSessionImages_should_recursively_delete_entire_directory(@TempDir Path workingDir) throws IOException {
        String sessionId = "sess-to-delete";
        store.save(workingDir.toString(), sessionId, pngBytes());
        store.save(workingDir.toString(), sessionId, jpgBytes());
        Path sessionDir = workingDir.resolve(UploadPicStore.SUBDIR).resolve(sessionId);
        assertTrue(Files.exists(sessionDir));

        store.deleteSessionImages(workingDir.toString(), sessionId);

        assertFalse(Files.exists(sessionDir), "sessionId 子目录应被删除");
        assertTrue(Files.exists(workingDir.resolve(UploadPicStore.SUBDIR)),
                "upload_pic 父目录应保留(其他 session 仍可能在用)");
    }

    @Test
    public void save_in_worktree_workingdir_should_lift_to_workspace_root_outside_worktrees(@TempDir Path workspaceRoot) throws IOException {
        // worktree 会话: workingDir 落在短命的 .worktrees/u-x/branch 内; 图片须上提到 workspace 根, 否则删分支即丢
        Path worktreeDir = workspaceRoot.resolve(".worktrees").resolve("u-x").resolve("branch");
        Files.createDirectories(worktreeDir);
        String sessionId = "sess-wt-1";

        String saved = store.save(worktreeDir.toString(), sessionId, pngBytes());

        Path savedPath = Paths.get(saved);
        Path expectedDir = workspaceRoot.resolve(UploadPicStore.SUBDIR).resolve(sessionId);
        assertTrue(savedPath.startsWith(expectedDir),
                "worktree 工作目录下的图片应上提到 workspace 根的 upload_pic/<sessionId>/: " + saved);
        assertFalse(savedPath.toString().contains(".worktrees"),
                "图片不应落在短命的 .worktrees 子树内: " + saved);
        assertTrue(Files.exists(savedPath));
    }

    @Test
    public void deleteSessionImages_in_worktree_workingdir_should_clean_lifted_location(@TempDir Path workspaceRoot) throws IOException {
        Path worktreeDir = workspaceRoot.resolve(".worktrees").resolve("u-x").resolve("branch");
        Files.createDirectories(worktreeDir);
        String sessionId = "sess-wt-del";
        store.save(worktreeDir.toString(), sessionId, pngBytes());
        Path liftedDir = workspaceRoot.resolve(UploadPicStore.SUBDIR).resolve(sessionId);
        assertTrue(Files.exists(liftedDir), "前置: 上提后的落点应已建立");

        store.deleteSessionImages(worktreeDir.toString(), sessionId);

        assertFalse(Files.exists(liftedDir), "删会话应能从上提后的落点清理图片");
    }

    @Test
    public void deleteSessionImages_dir_not_exist_should_silently_pass(@TempDir Path workingDir) {
        // 没上传过图片的会话,删除时不应抛异常
        store.deleteSessionImages(workingDir.toString(), "never-existed");
    }

    @Test
    public void deleteSessionImages_empty_session_id_should_silently_not_delete_parent_dir(@TempDir Path workingDir) throws IOException {
        // 防御:null/空 sessionId 不应误删整个 upload_pic
        store.save(workingDir.toString(), null, pngBytes());
        Path uploadPic = workingDir.resolve(UploadPicStore.SUBDIR);

        store.deleteSessionImages(workingDir.toString(), null);
        store.deleteSessionImages(workingDir.toString(), "");

        assertTrue(Files.exists(uploadPic), "空 sessionId 不应清掉 upload_pic 父目录");
    }
}
