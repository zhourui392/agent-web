package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.attachment.UploadedAttachmentStorageException;
import com.example.agentweb.app.workbench.attachment.port.StoredUploadedAttachment;
import com.example.agentweb.app.workbench.attachment.port.UploadedAttachmentStorageRequest;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 上传临时存储的原子写、魔数、边界、NOFOLLOW 与复制重验测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class FileSystemUploadedConversationAttachmentStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAtomicallyStoreAndCopyVerifiedTextWithoutExposingRoot() throws Exception {
        Path root = tempDir.resolve("data/workbench-uploads");
        FileSystemUploadedConversationAttachmentStorage storage =
                new FileSystemUploadedConversationAttachmentStorage(root, 1024L);
        byte[] content = "class Example {}\n".getBytes(StandardCharsets.UTF_8);

        StoredUploadedAttachment stored = storage.store(
                new UploadedAttachmentStorageRequest(
                        new ByteArrayInputStream(content), content.length));
        Path runtimeDirectory = Files.createDirectory(tempDir.resolve("runtime"));
        Path destination = runtimeDirectory.resolve("attachment.java");
        storage.copyVerified(stored.getStorageKey(), destination,
                stored.getSha256(), stored.getSize());

        assertEquals(UploadedAttachmentContentSignature.TEXT,
                stored.getContentSignature());
        assertArrayEquals(content, Files.readAllBytes(destination));
        assertFalse(stored.toString().contains(root.toString()));
        assertFalse(storage.toString().contains(root.toString()));
    }

    @Test
    void shouldDetectExecutablesAndRejectEmptyOrOversizedStreams() {
        FileSystemUploadedConversationAttachmentStorage storage =
                new FileSystemUploadedConversationAttachmentStorage(
                        tempDir.resolve("uploads"), 8L);

        StoredUploadedAttachment executable = storage.store(
                new UploadedAttachmentStorageRequest(
                        new ByteArrayInputStream(new byte[] {'M', 'Z', 0, 1}), 4L));
        assertEquals(UploadedAttachmentContentSignature.PE_EXECUTABLE,
                executable.getContentSignature());
        assertThrows(UploadedAttachmentStorageException.class,
                () -> storage.store(new UploadedAttachmentStorageRequest(
                        new ByteArrayInputStream(new byte[0]), 0L)));
        assertThrows(UploadedAttachmentStorageException.class,
                () -> storage.store(new UploadedAttachmentStorageRequest(
                        new ByteArrayInputStream(new byte[9]), 9L)));
    }

    @Test
    void changedSymlinkedOrHashMismatchedStoredObjectShouldFailClosed() throws Exception {
        Path root = tempDir.resolve("uploads-safe");
        FileSystemUploadedConversationAttachmentStorage storage =
                new FileSystemUploadedConversationAttachmentStorage(root, 1024L);
        byte[] content = "approved".getBytes(StandardCharsets.UTF_8);
        StoredUploadedAttachment stored = storage.store(
                new UploadedAttachmentStorageRequest(
                        new ByteArrayInputStream(content), content.length));
        Path runtimeDirectory = Files.createDirectory(tempDir.resolve("runtime-safe"));

        assertThrows(UploadedAttachmentStorageException.class,
                () -> storage.copyVerified(stored.getStorageKey(),
                        runtimeDirectory.resolve("wrong.txt"),
                        repeat('f'), stored.getSize()));

        Path storedPath = root.resolve(stored.getStorageKey());
        Files.delete(storedPath);
        Files.createSymbolicLink(storedPath,
                Files.write(tempDir.resolve("outside"), content));
        assertThrows(UploadedAttachmentStorageException.class,
                () -> storage.copyVerified(stored.getStorageKey(),
                        runtimeDirectory.resolve("symlink.txt"),
                        stored.getSha256(), stored.getSize()));
    }

    private String repeat(char value) {
        return String.join("", Collections.nCopies(64,
                Character.toString(value)));
    }
}
