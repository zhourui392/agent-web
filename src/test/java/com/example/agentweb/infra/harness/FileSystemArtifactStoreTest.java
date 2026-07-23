package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactStoreException;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.HarnessStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Artifact Store 原子写入、Hash 校验和路径逃逸测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class FileSystemArtifactStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void store_and_read_should_round_trip_content_without_using_raw_ids_as_paths() throws Exception {
        Path root = tempDir.resolve("artifacts");
        FileSystemArtifactStore store = new FileSystemArtifactStore(root);
        ArtifactContent content = ArtifactContent.from("safe".getBytes(StandardCharsets.UTF_8));
        ArtifactDescriptor descriptor = descriptor("../../escape", "../run", content);

        store.store(descriptor, content);

        assertArrayEquals(content.copyBytes(), store.read(descriptor).copyBytes());
        assertEquals(1L, Files.walk(root).filter(Files::isRegularFile).count());
        assertEquals(0L, Files.walk(tempDir).filter(path -> path.getFileName().toString().equals("escape")).count());
    }

    @Test
    void store_should_reject_descriptor_content_hash_mismatch() {
        FileSystemArtifactStore store = new FileSystemArtifactStore(tempDir.resolve("artifacts"));
        ArtifactContent expected = ArtifactContent.from("expected".getBytes(StandardCharsets.UTF_8));
        ArtifactContent actual = ArtifactContent.from("actual".getBytes(StandardCharsets.UTF_8));

        assertThrows(ArtifactStoreException.class,
                () -> store.store(descriptor("artifact", "run", expected), actual));
    }

    @Test
    void read_should_detect_tampered_file() throws Exception {
        Path root = tempDir.resolve("artifacts");
        FileSystemArtifactStore store = new FileSystemArtifactStore(root);
        ArtifactContent content = ArtifactContent.from("original".getBytes(StandardCharsets.UTF_8));
        ArtifactDescriptor descriptor = descriptor("artifact", "run", content);
        store.store(descriptor, content);
        Path stored = Files.walk(root).filter(Files::isRegularFile).findFirst().orElseThrow(AssertionError::new);
        Files.write(stored, "tampered".getBytes(StandardCharsets.UTF_8));

        assertThrows(ArtifactStoreException.class, () -> store.read(descriptor));
    }

    @Test
    void store_should_restrict_sensitive_directory_and_file_permissions() throws Exception {
        Path root = tempDir.resolve("artifacts");
        Assumptions.assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        FileSystemArtifactStore store = new FileSystemArtifactStore(root);
        ArtifactContent content = ArtifactContent.from("sensitive".getBytes(StandardCharsets.UTF_8));
        ArtifactDescriptor descriptor = new ArtifactDescriptor("artifact", ArtifactType.REQUIREMENT, 1,
                "run", HarnessStage.ANALYSIS, 1, "text/markdown", content.getSizeBytes(),
                content.getSha256(), ArtifactClassification.SENSITIVE, "admin",
                Instant.parse("2026-07-23T00:00:00Z"), Collections.<ArtifactReference>emptyList());

        store.store(descriptor, content);
        Path stored = Files.walk(root).filter(Files::isRegularFile).findFirst().orElseThrow(AssertionError::new);

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(stored));
    }

    private ArtifactDescriptor descriptor(String artifactId, String runId, ArtifactContent content) {
        return new ArtifactDescriptor(artifactId, ArtifactType.REQUIREMENT, 1,
                runId, HarnessStage.ANALYSIS, 1, "text/markdown",
                content.getSizeBytes(), content.getSha256(), ArtifactClassification.INTERNAL,
                "admin", Instant.parse("2026-07-23T00:00:00Z"),
                Collections.<ArtifactReference>emptyList());
    }
}
