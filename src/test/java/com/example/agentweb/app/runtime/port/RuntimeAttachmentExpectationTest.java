package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runtime 私有附件期望值的路径、摘要和脱敏合同。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeAttachmentExpectationTest {

    @Test
    void repositoryRootMustBeAbsoluteAndNormalized() {
        assertThrows(IllegalArgumentException.class,
                () -> expectation("workspace/repository", "docs/design.md",
                        CanonicalHashing.sha256("approved"), 8L));
        assertThrows(IllegalArgumentException.class,
                () -> expectation("/workspace/../workspace/repository",
                        "docs/design.md",
                        CanonicalHashing.sha256("approved"), 8L));
    }

    @Test
    void logicalPathsRejectTraversalAbsolutePathsAndControlCharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> expectation("/workspace/repository", "../design.md",
                        CanonicalHashing.sha256("approved"), 8L));
        assertThrows(IllegalArgumentException.class,
                () -> expectation("/workspace/repository", "/docs/design.md",
                        CanonicalHashing.sha256("approved"), 8L));
        assertThrows(IllegalArgumentException.class,
                () -> expectation("/workspace/repository", "docs/\ndesign.md",
                        CanonicalHashing.sha256("approved"), 8L));
    }

    @Test
    void contentHashAndSizeMustMatchBoundedSha256Contract() {
        assertThrows(IllegalArgumentException.class,
                () -> expectation("/workspace/repository", "docs/design.md",
                        "not-a-sha-256", 8L));
        assertThrows(IllegalArgumentException.class,
                () -> expectation("/workspace/repository", "docs/design.md",
                        CanonicalHashing.sha256("approved").toUpperCase(), 8L));
        assertThrows(IllegalArgumentException.class,
                () -> expectation("/workspace/repository", "docs/design.md",
                        CanonicalHashing.sha256("approved"), -1L));
        assertThrows(IllegalArgumentException.class,
                () -> expectation("/workspace/repository", "docs/design.md",
                        CanonicalHashing.sha256("approved"),
                        (long) Integer.MAX_VALUE + 1L));
    }

    @Test
    void stringRepresentationMustNotExposePathsOrHash() {
        String root = "/workspace/private-repository";
        String relativePath = "docs/private-design.md";
        String hash = CanonicalHashing.sha256("approved");

        String rendered = expectation(root, relativePath, hash, 8L).toString();

        assertFalse(rendered.contains(root));
        assertFalse(rendered.contains(relativePath));
        assertFalse(rendered.contains(hash));
    }

    private RuntimeAttachmentExpectation expectation(
            String repositoryRoot, String relativePath,
            String contentHash, long size) {
        return new RuntimeAttachmentExpectation(
                "repository", repositoryRoot, relativePath,
                contentHash, size);
    }
}
