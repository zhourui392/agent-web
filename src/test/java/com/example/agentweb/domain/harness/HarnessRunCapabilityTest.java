package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Attempt 与 Capability Snapshot 绑定前置条件测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class HarnessRunCapabilityTest {

    @Test
    void shouldExposeOnlyRunningAttemptAsCapabilitySnapshotTarget() {
        HarnessRun run = HarnessRun.create("run-1", "title", "/workspace", "CODEX", "test",
                "harness@1.0.0", "admin", "create-1", StageContract.mvpDefaults(),
                Instant.parse("2026-07-23T10:00:00Z"));

        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.capabilitySnapshotAttempt(HarnessStage.ANALYSIS));

        run.startStage(HarnessStage.ANALYSIS, "start-1", Instant.parse("2026-07-23T10:01:00Z"));

        assertEquals(1, run.capabilitySnapshotAttempt(HarnessStage.ANALYSIS));
        assertEquals(AgentRuntime.CODEX, run.capabilityRuntime());
        assertEquals("test", run.capabilityEnvironment());
        assertEquals(HarnessStage.ANALYSIS, run.capabilityStageContract(HarnessStage.ANALYSIS).getStage());
    }

    @Test
    void shouldRejectUnknownRuntimeAtDomainBoundary() {
        HarnessRun run = HarnessRun.create("run-2", "title", "/workspace", "UNKNOWN", "test",
                "harness@1.0.0", "admin", "create-2", StageContract.mvpDefaults(),
                Instant.parse("2026-07-23T10:00:00Z"));

        CapabilityResolutionException error = assertThrows(CapabilityResolutionException.class,
                run::capabilityRuntime);

        assertEquals("RUNTIME_UNSUPPORTED", error.getCode());
    }
}
