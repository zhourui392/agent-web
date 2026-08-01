package com.example.agentweb.infra.workspace.document;

import com.example.agentweb.app.workbench.document.DocumentFailureCode;
import com.example.agentweb.app.workbench.document.DocumentOperationException;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 文档稳定读取、TOCTOU 重试和有界读取测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class StableDocumentReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readShouldReturnDefensiveRawSnapshotAndFullSourceHash() throws Exception {
        Path file = tempDir.resolve("README.md");
        byte[] source = "stable".getBytes(StandardCharsets.UTF_8);
        Files.write(file, source);

        StableDocumentSnapshot snapshot = new StableDocumentReader()
                .read(() -> file, 1024);
        byte[] first = snapshot.getContent();
        first[0] = 'X';

        assertAll(
                () -> assertArrayEquals(source, snapshot.getContent()),
                () -> assertEquals(source.length, snapshot.getSize()),
                () -> assertEquals(CanonicalHashing.sha256(source),
                        snapshot.getContentVersion()));
    }

    @Test
    void readShouldRestartFromResolverAndReturnSecondStableVersion() throws Exception {
        Path file = tempDir.resolve("changing.txt");
        Files.write(file, "first".getBytes(StandardCharsets.UTF_8));
        StableDocumentReader reader = new StableDocumentReader((path, attempt) -> {
            if (attempt == 1) {
                Files.write(path, "second-version".getBytes(StandardCharsets.UTF_8));
            }
        });

        StableDocumentSnapshot snapshot = reader.read(() -> file, 1024);

        assertEquals("second-version",
                new String(snapshot.getContent(), StandardCharsets.UTF_8));
    }

    @Test
    void readShouldRejectSecondUnstableAttempt() throws Exception {
        Path file = tempDir.resolve("always-changing.txt");
        Files.write(file, "a".getBytes(StandardCharsets.UTF_8));
        StableDocumentReader reader = new StableDocumentReader((path, attempt) ->
                Files.write(path, new byte[]{(byte) ('a' + attempt)},
                        StandardOpenOption.APPEND));

        DocumentOperationException failure = assertThrows(
                DocumentOperationException.class,
                () -> reader.read(() -> file, 1024));

        assertEquals(DocumentFailureCode.WORKBENCH_DOCUMENT_CHANGED_DURING_READ,
                failure.getCode());
    }

    @Test
    void readShouldTreatDeletionAndNonRegularPathAsNotFound() throws Exception {
        Path file = tempDir.resolve("deleted.txt");
        Files.write(file, "gone".getBytes(StandardCharsets.UTF_8));
        StableDocumentReader deleting = new StableDocumentReader((path, attempt) ->
                Files.deleteIfExists(path));

        DocumentOperationException deleted = assertThrows(
                DocumentOperationException.class,
                () -> deleting.read(() -> file, 1024));
        DocumentOperationException directory = assertThrows(
                DocumentOperationException.class,
                () -> new StableDocumentReader().read(() -> tempDir, 1024));

        assertEquals(DocumentFailureCode.WORKBENCH_DOCUMENT_NOT_FOUND,
                deleted.getCode());
        assertEquals(DocumentFailureCode.WORKBENCH_DOCUMENT_NOT_FOUND,
                directory.getCode());
    }

    @Test
    void readShouldRejectSourceBeyondConfiguredBoundBeforeUnboundedRead() throws Exception {
        Path file = tempDir.resolve("large.txt");
        Files.write(file, "12345".getBytes(StandardCharsets.UTF_8));

        DocumentOperationException failure = assertThrows(
                DocumentOperationException.class,
                () -> new StableDocumentReader().read(() -> file, 4));

        assertEquals(DocumentFailureCode.WORKBENCH_DOCUMENT_TOO_LARGE,
                failure.getCode());
    }
}
