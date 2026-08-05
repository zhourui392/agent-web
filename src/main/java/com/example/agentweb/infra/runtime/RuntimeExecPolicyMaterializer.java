package com.example.agentweb.infra.runtime;

import com.example.agentweb.domain.runtime.RuntimeCommandPolicy;
import com.example.agentweb.domain.runtime.RuntimeCommandPrefix;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 把平台高影响操作拒绝策略物化到单次隔离 CODEX_HOME。
 *
 * @author alex
 * @since 2026-08-01
 */
final class RuntimeExecPolicyMaterializer {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final String JUSTIFICATION =
            "High-impact commands are unavailable in Workbench Runtime.";
    private static final String OPAQUE_SHELL_JUSTIFICATION =
            "Use a linear command without shell indirection; high-impact "
                    + "commands are unavailable in Workbench Runtime.";
    private static final List<String> EXECUTABLE_SUFFIXES =
            Collections.unmodifiableList(java.util.Arrays.asList(
                    "", ".exe", ".cmd", ".bat"));

    private final RuntimeCommandPolicy commandPolicy;
    private final List<Path> executableSearchDirectories;

    RuntimeExecPolicyMaterializer(RuntimeCommandPolicy commandPolicy) {
        this(commandPolicy, systemExecutableSearchDirectories());
    }

    RuntimeExecPolicyMaterializer(
            RuntimeCommandPolicy commandPolicy,
            List<Path> executableSearchDirectories) {
        this.commandPolicy = Objects.requireNonNull(
                commandPolicy, "commandPolicy");
        this.executableSearchDirectories = immutableSearchDirectories(
                executableSearchDirectories);
    }

    Path materialize(Path isolatedHome) throws IOException {
        Objects.requireNonNull(isolatedHome, "isolatedHome");
        Path rulesDirectory = isolatedHome.resolve("rules");
        Files.createDirectory(rulesDirectory);
        secure(rulesDirectory, DIRECTORY_PERMISSIONS);
        Path policyFile = rulesDirectory.resolve("default.rules");
        Files.write(policyFile, render().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        secure(policyFile, FILE_PERMISSIONS);
        return policyFile;
    }

    private String render() {
        StringBuilder policy = new StringBuilder();
        for (RuntimeCommandPrefix prefix : commandPolicy.getForbiddenPrefixes()) {
            appendRule(policy, prefix.getTokens(), JUSTIFICATION);
            appendAbsoluteRules(policy, prefix.getTokens(), JUSTIFICATION);
        }
        for (List<String> prefix
                : commandPolicy.getOpaqueShellWrapperPrefixes()) {
            appendRule(policy, prefix, OPAQUE_SHELL_JUSTIFICATION);
            appendAbsoluteRules(
                    policy, prefix, OPAQUE_SHELL_JUSTIFICATION);
        }
        return policy.toString();
    }

    private void appendAbsoluteRules(
            StringBuilder policy, List<String> tokens,
            String justification) {
        if (tokens.isEmpty()) {
            return;
        }
        for (Path executable : resolveExecutables(tokens.get(0))) {
            List<String> absoluteTokens =
                    new ArrayList<String>(tokens);
            absoluteTokens.set(0, executable.toString());
            appendRule(policy, absoluteTokens, justification);
        }
    }

    private void appendRule(
            StringBuilder policy, List<String> tokens,
            String justification) {
        policy.append("prefix_rule(pattern=[");
        for (int index = 0; index < tokens.size(); index++) {
            if (index > 0) {
                policy.append(',');
            }
            policy.append('"').append(escape(tokens.get(index))).append('"');
        }
        policy.append("],decision=\"forbidden\",justification=\"")
                .append(escape(justification))
                .append("\")\n");
    }

    private List<Path> resolveExecutables(String program) {
        Set<Path> resolved = new LinkedHashSet<Path>();
        for (Path directory : executableSearchDirectories) {
            for (String suffix : EXECUTABLE_SUFFIXES) {
                Path candidate = directory.resolve(program + suffix)
                        .toAbsolutePath().normalize();
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                resolved.add(candidate);
                try {
                    resolved.add(candidate.toRealPath());
                } catch (IOException ignored) {
                    // 真实路径无法稳定解析时仍保留已验证的绝对候选路径。
                }
            }
        }
        return new ArrayList<Path>(resolved);
    }

    private static List<Path> immutableSearchDirectories(
            List<Path> directories) {
        Objects.requireNonNull(directories, "executableSearchDirectories");
        List<Path> normalized = new ArrayList<Path>();
        for (Path directory : directories) {
            if (directory != null && directory.isAbsolute()) {
                normalized.add(directory.normalize());
            }
        }
        return Collections.unmodifiableList(normalized);
    }

    private static List<Path> systemExecutableSearchDirectories() {
        String value = System.getenv("PATH");
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> directories = new ArrayList<Path>();
        for (String entry : value.split(
                java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            Path directory = Paths.get(entry).toAbsolutePath().normalize();
            if (!directories.contains(directory)) {
                directories.add(directory);
            }
        }
        return directories;
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private void secure(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows ACL 由本机服务账户边界承担。
        }
    }
}
