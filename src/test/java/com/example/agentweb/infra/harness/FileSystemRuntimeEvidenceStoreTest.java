package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.port.AgentExecutionSpec;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.HarnessHashing;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.RuntimeEnforcementProfile;
import com.example.agentweb.domain.harness.WorkspaceRuntimeInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime JSONL 正文委托受控 Artifact Store 落盘的测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class FileSystemRuntimeEvidenceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldStoreJsonlInArtifactRootAndReturnLogicalReference() throws Exception {
        Path root = tempDir.resolve("artifacts");
        FileSystemRuntimeEvidenceStore evidenceStore = new FileSystemRuntimeEvidenceStore(
                new FileSystemArtifactStore(root));
        byte[] jsonl = "{\"type\":\"turn.completed\"}\n".getBytes(StandardCharsets.UTF_8);

        String reference = evidenceStore.store(spec(), jsonl,
                Instant.parse("2026-07-23T16:00:00Z"));

        Path stored = Files.walk(root).filter(Files::isRegularFile)
                .findFirst().orElseThrow(AssertionError::new);
        assertArrayEquals(jsonl, Files.readAllBytes(stored));
        assertTrue(reference.startsWith("artifact:runtime-jsonl-exec-1:1:"));
        assertFalse(reference.contains(root.toString()));
    }

    private AgentExecutionSpec spec() {
        String prompt = "prompt";
        return new AgentExecutionSpec("exec-1", "run-1", HarnessStage.ANALYSIS, 1,
                AgentRuntime.CODEX, tempDir.toString(), prompt, HarnessHashing.sha256("snapshot"),
                HarnessHashing.sha256(prompt), Collections.emptyList(),
                new RuntimeEnforcementProfile("codex@1", "codex-test", "read-only",
                        true, true, true), WorkspaceRuntimeInventory.empty(),
                com.example.agentweb.domain.harness.StageContract.mvpDefaults().get(0)
                        .getRequiredOutputArtifacts());
    }
}
