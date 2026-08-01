package com.example.agentweb.domain.workspace;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace 相对路径敏感信息策略的大小写、边界与相似名称测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkspaceSensitivePathPolicyTest {

    @Test
    void givenSensitivePathsWithMixedCaseWhenClassifyThenReturnTrue() {
        List<String> sensitivePaths = Arrays.asList(
                ".env",
                ".EnV.Production",
                "config/.ENV.local",
                "ENV.LOCAL",
                "DATA/credentials.json",
                ".CoDeX/config.toml",
                ".CLAUDE/settings.json",
                ".GiT/config",
                "config/team-SECRETS.PROPERTIES.backup",
                "nested/secrets.properties/value",
                "tls/server.PEM.backup",
                "keys/client.KEY.old",
                "cert.pem/public.txt",
                "api.key/config.json");

        for (String path : sensitivePaths) {
            assertTrue(WorkspaceSensitivePathPolicy.isSensitive(path), path);
        }
    }

    @Test
    void givenSimilarButNonSensitivePathsWhenClassifyThenReturnFalse() {
        List<String> allowedPaths = Arrays.asList(
                ".gitignore",
                "database/orders.db",
                "data.txt",
                "monkey",
                "public-key.txt",
                "docs/keynote.md",
                "metadata/config.json");

        for (String path : allowedPaths) {
            assertFalse(WorkspaceSensitivePathPolicy.isSensitive(path), path);
        }
    }
}
