package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.setting.WorkspaceSettingsQueryService;
import com.example.agentweb.domain.setting.WorkspaceSettings;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import com.example.agentweb.infra.RealPathWorkspacePolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * Workspace 真实 Git 测试夹具。
 *
 * @author alex
 * @since 2026-08-01
 */
final class GitWorkspaceTestSupport {

    private GitWorkspaceTestSupport() {
    }

    static Path repository(Path workspaceRoot, String relativePath) throws Exception {
        Path repository = workspaceRoot.resolve(relativePath);
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.email", "workbench@example.invalid");
        git(repository, "config", "user.name", "Workbench Test");
        Files.write(repository.resolve("README.md"),
                ("# " + relativePath).getBytes(StandardCharsets.UTF_8));
        git(repository, "add", "README.md");
        git(repository, "commit", "-m", "initial");
        return repository;
    }

    static WorkspacePathPolicy allowedUnder(Path allowedRoot) {
        WorkspaceSettings settings = WorkspaceSettings.create(
                allowedRoot.toString(), Collections.singletonList(allowedRoot.toString()),
                Collections.<String>emptyList());
        WorkspaceSettingsQueryService queryService = () -> settings;
        return new RealPathWorkspacePolicy(queryService);
    }

    static String git(Path directory, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("git fixture command failed with " + exitCode
                    + ": " + new String(output, StandardCharsets.UTF_8));
        }
        return new String(output, StandardCharsets.UTF_8);
    }
}
