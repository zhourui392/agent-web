package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Harness 各写命令的非法状态、合同越权和不完整门禁测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class HarnessRunInvalidTransitionTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    private HarnessRun run;

    @BeforeEach
    void setUp() {
        run = HarnessRun.create("run-1", "M1", "/workspace", "CODEX", "local",
                "harness@1.0.0", "admin", "create-key", StageContract.mvpDefaults(), NOW);
    }

    @Test
    void pending_stage_should_reject_gate_artifact_approval_and_retry_commands() {
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.recordGate(HarnessStage.ANALYSIS, "gate-1",
                        "artifact-schema-valid", true, Collections.<String>emptyList(),
                        null, NOW.plusSeconds(1)));
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.registerArtifact(HarnessStage.ANALYSIS, "artifact-1",
                        ArtifactType.REQUIREMENT, content("requirement"), "text/markdown",
                        ArtifactClassification.INTERNAL, "admin",
                        Collections.<ArtifactReference>emptyList(), NOW.plusSeconds(1)));
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.submitForApproval(HarnessStage.ANALYSIS, NOW.plusSeconds(1)));
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.approve(HarnessStage.ANALYSIS, "approval-1", hash('a'),
                        "admin", "premature", NOW.plusSeconds(1)));
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.retryStage(HarnessStage.ANALYSIS, "retry", NOW.plusSeconds(1)));
    }

    @Test
    void running_stage_should_reject_out_of_contract_artifact_and_gate() {
        run.startStage(HarnessStage.ANALYSIS, "start", NOW.plusSeconds(1));

        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.registerArtifact(HarnessStage.ANALYSIS, "solution",
                        ArtifactType.SOLUTION, content("solution"), "text/markdown",
                        ArtifactClassification.INTERNAL, "admin",
                        Collections.<ArtifactReference>emptyList(), NOW.plusSeconds(2)));
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.recordGate(HarnessStage.ANALYSIS, "unknown-gate", "unknown", true,
                        Collections.<String>emptyList(), null, NOW.plusSeconds(2)));
    }

    @Test
    void request_approval_should_reject_missing_artifacts_then_missing_gates() {
        run.startStage(HarnessStage.ANALYSIS, "start", NOW.plusSeconds(1));

        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.submitForApproval(HarnessStage.ANALYSIS, NOW.plusSeconds(2)));

        int offset = 3;
        for (ArtifactType type : run.stage(HarnessStage.ANALYSIS)
                .getContract().getRequiredOutputArtifacts()) {
            run.registerArtifact(HarnessStage.ANALYSIS, "artifact-" + type, type,
                    content(type.name()), "text/markdown", ArtifactClassification.INTERNAL,
                    "admin", Collections.<ArtifactReference>emptyList(), NOW.plusSeconds(offset++));
        }

        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.submitForApproval(HarnessStage.ANALYSIS, NOW.plusSeconds(20)));
    }

    @Test
    void failed_stage_should_require_retry_command_instead_of_start() {
        run.startStage(HarnessStage.ANALYSIS, "start", NOW.plusSeconds(1));
        run.registerArtifact(HarnessStage.ANALYSIS, "requirement", ArtifactType.REQUIREMENT,
                content("requirement"), "text/markdown", ArtifactClassification.INTERNAL,
                "admin", Collections.<ArtifactReference>emptyList(), NOW.plusSeconds(2));
        run.recordGate(HarnessStage.ANALYSIS, "gate-fail", "artifact-schema-valid", false,
                Collections.<String>emptyList(), "invalid schema", NOW.plusSeconds(3));

        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.startStage(HarnessStage.ANALYSIS, "start-again", NOW.plusSeconds(4)));
    }

    private ArtifactContent content(String value) {
        return ArtifactContent.from(value.getBytes(StandardCharsets.UTF_8));
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
