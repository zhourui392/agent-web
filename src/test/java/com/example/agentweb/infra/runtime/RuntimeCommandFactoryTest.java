package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.SandboxMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codex 命令 token、Sandbox 与多仓目录边界契约。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeCommandFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void createsReadOnlyCodexTokensForEverySelectedReadableRootOnlyInLayoutOrder()
            throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary"));
        Path readableSecond = Files.createDirectory(tempDir.resolve("readable-second"));
        Path readableFirst = Files.createDirectory(tempDir.resolve("readable-first"));
        Path undeclaredSibling = Files.createDirectory(
                tempDir.resolve("undeclared-sibling"));
        AgentExecutionPlan plan = RuntimePlanFixtures.readOnly("exec-read", primary,
                Arrays.asList(primary, readableSecond, readableFirst));
        RuntimeWorkspaceMaterializer materializer =
                new RuntimeWorkspaceMaterializer(tempDir.resolve("runtime"));
        RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace =
                materializer.materialize(plan);

        List<String> tokens = new RuntimeCommandFactory("codex").create(plan, workspace);

        assertEquals("codex", tokens.get(0));
        assertOrdered(tokens, "--ask-for-approval", "never", "exec",
                "--ephemeral", "--json");
        assertOption(tokens, "-c", "allow_login_shell=false");
        assertFalse(tokens.contains("--ignore-rules"));
        assertFalse(tokens.contains("--ignore-user-config"));
        assertOption(tokens, "--sandbox", "read-only");
        assertOption(tokens, "--cd", primary.toRealPath().toString());
        assertEquals(Arrays.asList(
                        readableSecond.toRealPath().toString(),
                        readableFirst.toRealPath().toString()),
                optionValues(tokens, "--add-dir"));
        assertFalse(tokens.contains(undeclaredSibling.toRealPath().toString()));
        assertFalse(tokens.contains(tempDir.toRealPath().toString()));
        assertEquals("-", tokens.get(tokens.size() - 1));
    }

    @Test
    void createsWorkspaceWriteTokensForPrimaryAndEveryWritableExtraRepository()
            throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-write"));
        Path writableSecond = Files.createDirectory(tempDir.resolve("writable-second"));
        Path writableFirst = Files.createDirectory(tempDir.resolve("writable-first"));
        Path readableExtra = Files.createDirectory(tempDir.resolve("readable-extra"));
        AgentExecutionPlan plan = RuntimePlanFixtures.plan("exec-write", primary,
                Arrays.asList(primary, writableSecond, readableExtra, writableFirst),
                Arrays.asList(primary, writableSecond, writableFirst),
                SandboxMode.WORKSPACE_WRITE,
                Duration.ofSeconds(5L), 1024L);
        RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace =
                new RuntimeWorkspaceMaterializer(tempDir.resolve("runtime-write"))
                        .materialize(plan);

        List<String> tokens = new RuntimeCommandFactory("codex").create(plan, workspace);

        assertOption(tokens, "--sandbox", "workspace-write");
        assertOption(tokens, "--cd", primary.toRealPath().toString());
        assertEquals(Arrays.asList(
                        writableSecond.toRealPath().toString(),
                        writableFirst.toRealPath().toString()),
                optionValues(tokens, "--add-dir"));
        assertFalse(optionValues(tokens, "--add-dir").contains(
                readableExtra.toRealPath().toString()));
    }

    @Test
    void commandTokensAreImmutableAndRejectControlCharacters() throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-safe"));
        AgentExecutionPlan plan = RuntimePlanFixtures.readOnly("exec-safe", primary,
                Collections.singletonList(primary));
        RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace =
                new RuntimeWorkspaceMaterializer(tempDir.resolve("runtime-safe"))
                        .materialize(plan);

        List<String> tokens = new RuntimeCommandFactory("codex").create(plan, workspace);

        assertThrows(UnsupportedOperationException.class, () -> tokens.add("unsafe"));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeCommandFactory("codex\nmalicious"));
    }

    private void assertOrdered(List<String> tokens, String... expected) {
        int cursor = -1;
        for (String token : expected) {
            int found = tokens.subList(cursor + 1, tokens.size()).indexOf(token);
            assertTrue(found >= 0, "missing command token: " + token);
            cursor += found + 1;
        }
    }

    private void assertOption(List<String> tokens, String option, String value) {
        assertTrue(optionValues(tokens, option).contains(value),
                "missing option " + option + "=" + value + " in " + tokens);
    }

    private List<String> optionValues(List<String> tokens, String option) {
        java.util.ArrayList<String> values = new java.util.ArrayList<String>();
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (option.equals(tokens.get(index))) {
                values.add(tokens.get(index + 1));
            }
        }
        return values;
    }
}
