package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.CredentialReference;
import com.example.agentweb.app.runtime.port.SandboxMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时 Home 与声明仓库目录物化边界契约。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeWorkspaceMaterializerTest {

    @TempDir
    Path tempDir;

    @Test
    void materializesIsolatedHomeAndPreservesPrimaryReadableWritableOrdering()
            throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary"));
        Path extra = Files.createDirectory(tempDir.resolve("extra"));
        AgentExecutionPlan plan = RuntimePlanFixtures.plan("exec-layout", primary,
                Arrays.asList(primary, extra), Arrays.asList(primary, extra),
                SandboxMode.WORKSPACE_WRITE, Duration.ofSeconds(5L), 1024L,
                Collections.<String>emptySet(), CredentialReference.systemConfiguration());

        RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace =
                new RuntimeWorkspaceMaterializer(tempDir.resolve("runtime")).materialize(plan);

        assertTrue(Files.isDirectory(workspace.getExecutionRoot()));
        assertTrue(Files.isDirectory(workspace.getIsolatedHome()));
        Path policy = workspace.getIsolatedHome().resolve("rules/default.rules");
        assertTrue(Files.isRegularFile(policy));
        String policyText = new String(Files.readAllBytes(policy),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(policyText.contains(
                "prefix_rule(pattern=[\"git\",\"commit\"],decision=\"forbidden\""));
        assertTrue(policyText.contains(
                "prefix_rule(pattern=[\"git\",\"push\"],decision=\"forbidden\""));
        assertFalse(policyText.contains(primary.toString()));
        assertEquals(primary.toRealPath(), workspace.getPrimaryRepositoryRoot());
        assertEquals(Arrays.asList(primary.toRealPath(), extra.toRealPath()),
                workspace.getReadableRoots());
        assertEquals(Arrays.asList(primary.toRealPath(), extra.toRealPath()),
                workspace.getWritableRoots());
        assertFalse(workspace.getExecutionRoot().startsWith(primary));
    }

    @Test
    void rejectsMissingOrSymlinkAliasedRepositoryBeforeLaunch() throws Exception {
        Path missing = tempDir.resolve("missing");
        AgentExecutionPlan missingPlan = RuntimePlanFixtures.readOnly("exec-missing", missing,
                Collections.singletonList(missing), Collections.<String>emptySet(),
                CredentialReference.systemConfiguration());
        RuntimeWorkspaceMaterializer materializer =
                new RuntimeWorkspaceMaterializer(tempDir.resolve("runtime-missing"));

        assertThrows(IllegalStateException.class, () -> materializer.materialize(missingPlan));

        Path real = Files.createDirectory(tempDir.resolve("real"));
        Path alias = tempDir.resolve("alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException ex) {
            return;
        }
        AgentExecutionPlan aliasPlan = RuntimePlanFixtures.readOnly("exec-alias", alias,
                Collections.singletonList(alias), Collections.<String>emptySet(),
                CredentialReference.systemConfiguration());

        assertThrows(IllegalStateException.class, () -> materializer.materialize(aliasPlan));
    }

    @Test
    void rejectsProjectConfigurationThatCouldOverridePlatformExecPolicy() throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-policy"));
        Path projectConfig = Files.createDirectories(primary.resolve(".codex/rules"));
        Files.write(projectConfig.resolve("override.rules"),
                "prefix_rule(pattern=[\"git\"],decision=\"allow\")"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AgentExecutionPlan plan = RuntimePlanFixtures.readOnly(
                "exec-project-policy", primary,
                Collections.singletonList(primary),
                Collections.<String>emptySet(),
                CredentialReference.systemConfiguration());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new RuntimeWorkspaceMaterializer(
                        tempDir.resolve("runtime-project-policy"))
                        .materialize(plan));

        assertEquals("repository Runtime configuration is not allowed",
                failure.getMessage());
        assertFalse(failure.getMessage().contains(primary.toString()));
    }
}
