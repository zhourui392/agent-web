package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.WorkspaceBaseline;
import com.example.agentweb.domain.harness.WorkspaceChangeEvidence;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 Git 基线适配器真实仓库测试。
 *
 * @author alex
 * @since 2026-07-23
 */
@Tag("git-integration")
class ProcessWorkspaceBaselineGatewayTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void capture_should_include_root_branch_head_clean_state_and_dirty_content_hash() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-b", "m4-test");
        git(repo, "config", "user.email", "m4@example.invalid");
        git(repo, "config", "user.name", "M4 Test");
        Files.write(repo.resolve("tracked.txt"), "baseline".getBytes(StandardCharsets.UTF_8));
        git(repo, "add", "tracked.txt");
        git(repo, "commit", "-m", "baseline");
        String head = git(repo, "rev-parse", "HEAD").trim();
        ProcessWorkspaceBaselineGateway gateway = new ProcessWorkspaceBaselineGateway(
                Clock.fixed(NOW, ZoneOffset.UTC));

        WorkspaceBaseline clean = gateway.capture(repo.toString());
        Files.write(repo.resolve("tracked.txt"), "changed".getBytes(StandardCharsets.UTF_8));
        Files.write(repo.resolve("untracked.txt"), "first".getBytes(StandardCharsets.UTF_8));
        WorkspaceBaseline dirty = gateway.capture(repo.toString());
        Files.write(repo.resolve("tracked.txt"), "changed-again".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(repo.resolve("data"));
        Files.write(repo.resolve("data/secrets.properties"),
                "do-not-read".getBytes(StandardCharsets.UTF_8));
        WorkspaceChangeEvidence evidence = gateway.captureChanges(repo.toString(), dirty);
        Files.write(repo.resolve("untracked.txt"), "second".getBytes(StandardCharsets.UTF_8));
        WorkspaceBaseline changedAgain = gateway.capture(repo.toString());

        assertEquals(repo.toRealPath().toString(), clean.getRepositoryRoot());
        assertEquals("m4-test", clean.getBranch());
        assertEquals(head, clean.getHead());
        assertTrue(clean.isClean());
        assertFalse(dirty.isClean());
        assertNotEquals(clean.getDiffHash(), dirty.getDiffHash());
        assertNotEquals(dirty.getDiffHash(), changedAgain.getDiffHash());
        assertEquals(NOW, dirty.getCapturedAt());
        assertEquals(2, dirty.getFiles().size());
        assertTrue(evidence.getFiles().stream()
                .anyMatch(file -> file.getPath().equals("tracked.txt")));
        assertFalse(evidence.getFiles().stream()
                .anyMatch(file -> file.getPath().equals("untracked.txt")));
        assertTrue(evidence.getFiles().stream()
                .anyMatch(file -> file.getPath().equals("data/secrets.properties")
                        && file.isSensitive()));
        assertEquals(dirty.getDiffHash(), evidence.getBaseline().getDiffHash());
    }

    @Test
    void capture_should_fail_closed_outside_git_repository() {
        ProcessWorkspaceBaselineGateway gateway = new ProcessWorkspaceBaselineGateway(
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(WorkspaceBaselineCaptureException.class,
                () -> gateway.capture(tempDir.toString()));
    }

    private String git(Path directory, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git fixture command failed with " + exit);
        }
        return new String(output, StandardCharsets.UTF_8);
    }
}
