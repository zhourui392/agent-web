package com.example.agentweb.infra.runtime;

import com.example.agentweb.domain.runtime.RuntimeCommandPolicy;
import com.example.agentweb.domain.runtime.RuntimeCommandPrefix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
            "Use the Workbench typed high-impact operation flow.";

    private final RuntimeCommandPolicy commandPolicy;

    RuntimeExecPolicyMaterializer(RuntimeCommandPolicy commandPolicy) {
        this.commandPolicy = Objects.requireNonNull(
                commandPolicy, "commandPolicy");
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
            policy.append("prefix_rule(pattern=[");
            for (int index = 0; index < prefix.getTokens().size(); index++) {
                if (index > 0) {
                    policy.append(',');
                }
                policy.append('"').append(escape(
                        prefix.getTokens().get(index))).append('"');
            }
            policy.append("],decision=\"forbidden\",justification=\"")
                    .append(JUSTIFICATION)
                    .append("\")\n");
        }
        return policy.toString();
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
