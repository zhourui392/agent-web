package com.example.agentweb.domain.workspace;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 变更文件证据对统一 Workspace 敏感路径策略的委托与防伪测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ChangedFileEvidenceTest {

    private static final String FINGERPRINT = repeat('a', 64);

    @Test
    void givenWorkspacePathsWhenObserveThenUseSharedSensitivePathPolicy() {
        List<String> paths = Arrays.asList(
                ".ENV.local",
                "Data/credentials.json",
                ".Claude/settings.json",
                ".Git/config",
                "config/team-secrets.properties.backup",
                "tls/server.pem.old",
                ".gitignore",
                "database/orders.db",
                "data.txt",
                "monkey",
                "public-key.txt");

        for (String path : paths) {
            ChangedFileEvidence evidence = ChangedFileEvidence.observed(
                    path, "M", FINGERPRINT);

            assertEquals(WorkspaceSensitivePathPolicy.isSensitive(path),
                    evidence.isSensitive(), path);
        }
    }

    @Test
    void givenSensitivePathReportedAsFalseWhenConstructThenRejectSpoofedClassification() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChangedFileEvidence(
                        "config/SECRETS.PROPERTIES.backup",
                        "M", FINGERPRINT, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ChangedFileEvidence(".Claude/settings.json", false));
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }
}
