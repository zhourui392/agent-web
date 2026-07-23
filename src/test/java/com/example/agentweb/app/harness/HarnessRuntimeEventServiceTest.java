package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.RuntimeEvent;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilitySnapshotReference;
import com.example.agentweb.domain.harness.ExecutionPermit;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessRunStatus;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.IllegalHarnessTransitionException;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionEvent;
import com.example.agentweb.domain.harness.RuntimeExecutionRepository;
import com.example.agentweb.domain.harness.RuntimeExecutionSignal;
import com.example.agentweb.domain.harness.RuntimeExecutionStatus;
import com.example.agentweb.domain.harness.StageAttemptStatus;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.StageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runtime Event 应用入口的幂等编排与领域终态映射测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(MockitoExtension.class)
class HarnessRuntimeEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-23T16:00:00Z");

    @Mock
    private RuntimeExecutionRepository executionRepository;
    @Mock
    private HarnessRunRepository runRepository;

    private HarnessRuntimeEventService service;

    @BeforeEach
    void setUp() {
        service = new HarnessRuntimeEventService(executionRepository, runRepository,
                Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC));
    }

    @Test
    void duplicateSequenceShouldNotAppendOrUpdateAgain() {
        Fixture fixture = runningFixture();
        fixture.execution.apply(RuntimeExecutionSignal.output(2L, "evidence:first",
                NOW.plusSeconds(5)));
        when(executionRepository.findById("exec-1")).thenReturn(Optional.of(fixture.execution));

        service.onEvent(new RuntimeEvent("exec-1", RuntimeExecutionSignal.output(
                2L, "evidence:duplicate", NOW.plusSeconds(6)), "duplicate"));

        verify(executionRepository, never()).appendEvent(any(RuntimeExecutionEvent.class));
        verify(executionRepository, never()).update(any(RuntimeExecution.class));
        verify(runRepository, never()).findById(any(String.class));
    }

    @Test
    void startedAndOutputShouldPersistExecutionWithoutChangingStage() {
        Fixture fixture = startingFixture();
        stub(fixture);

        service.onEvent(new RuntimeEvent("exec-1", RuntimeExecutionSignal.started(
                1L, "codex-test", "process", NOW.plusSeconds(4)), "started"));
        service.onEvent(new RuntimeEvent("exec-1", RuntimeExecutionSignal.output(
                2L, "artifact:jsonl", NOW.plusSeconds(5)), "output"));

        assertEquals(RuntimeExecutionStatus.RUNNING, fixture.execution.getStatus());
        assertEquals("artifact:jsonl", fixture.execution.getEvidenceReference());
        assertEquals(StageStatus.RUNNING,
                fixture.run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(StageAttemptStatus.RUNNING,
                fixture.run.stage(HarnessStage.ANALYSIS).currentAttempt().getStatus());
        verify(executionRepository, org.mockito.Mockito.times(2))
                .appendEvent(any(RuntimeExecutionEvent.class));
        verify(runRepository, never()).update(any(HarnessRun.class));
    }

    @Test
    void succeededExecutionShouldNotMarkStagePassed() {
        Fixture fixture = runningFixture();
        stub(fixture);

        service.onEvent(new RuntimeEvent("exec-1", RuntimeExecutionSignal.succeeded(
                2L, 0, "artifact:result", true, NOW.plusSeconds(5)), "succeeded"));

        assertEquals(RuntimeExecutionStatus.SUCCEEDED, fixture.execution.getStatus());
        assertEquals(HarnessRunStatus.ACTIVE, fixture.run.getStatus());
        assertEquals(StageStatus.RUNNING,
                fixture.run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(StageAttemptStatus.RUNNING,
                fixture.run.stage(HarnessStage.ANALYSIS).currentAttempt().getStatus());
        verify(runRepository).update(fixture.run);
    }

    @Test
    void failedTimedOutAndLostShouldFailRunStageAndAttempt() {
        assertFailure(RuntimeExecutionSignal.failed(2L, 1, "failed", true,
                NOW.plusSeconds(5)), RuntimeExecutionStatus.FAILED);
        assertFailure(RuntimeExecutionSignal.timedOut(2L, "timed out", true,
                NOW.plusSeconds(5)), RuntimeExecutionStatus.TIMED_OUT);
        assertFailure(RuntimeExecutionSignal.lost(2L, "lost", NOW.plusSeconds(5)),
                RuntimeExecutionStatus.LOST);
    }

    @Test
    void cancelledSignalWithoutPersistedIntentShouldFailClosed() {
        Fixture fixture = runningFixture();
        when(executionRepository.findById("exec-1")).thenReturn(Optional.of(fixture.execution));

        assertThrows(IllegalHarnessTransitionException.class, () -> service.onEvent(
                new RuntimeEvent("exec-1", RuntimeExecutionSignal.cancelled(
                        2L, 0, "not requested", true, NOW.plusSeconds(5)), "cancelled")));

        assertEquals(RuntimeExecutionStatus.RUNNING, fixture.execution.getStatus());
        verify(executionRepository, never()).appendEvent(any(RuntimeExecutionEvent.class));
        verify(runRepository, never()).update(any(HarnessRun.class));
    }

    @Test
    void exitCodeZeroAfterPersistedCancellationShouldRemainCancelled() {
        Fixture fixture = runningFixture();
        fixture.run.requestCancellation("admin", "stop", NOW.plusSeconds(5));
        fixture.execution.requestCancellation("admin", "stop", NOW.plusSeconds(5));
        stub(fixture);

        service.onEvent(new RuntimeEvent("exec-1", RuntimeExecutionSignal.succeeded(
                2L, 0, "artifact:result", true, NOW.plusSeconds(6)), "process exited zero"));

        assertEquals(RuntimeExecutionStatus.CANCELLED, fixture.execution.getStatus());
        assertEquals(HarnessRunStatus.CANCELLED, fixture.run.getStatus());
        assertEquals(StageStatus.CANCELLED,
                fixture.run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(StageAttemptStatus.CANCELLED,
                fixture.run.stage(HarnessStage.ANALYSIS).currentAttempt().getStatus());
        verify(runRepository).update(fixture.run);
    }

    private void assertFailure(RuntimeExecutionSignal signal, RuntimeExecutionStatus expected) {
        org.mockito.Mockito.reset(executionRepository, runRepository);
        Fixture fixture = runningFixture();
        stub(fixture);

        service.onEvent(new RuntimeEvent("exec-1", signal, signal.getReason()));

        assertEquals(expected, fixture.execution.getStatus());
        assertEquals(HarnessRunStatus.FAILED, fixture.run.getStatus());
        assertEquals(StageStatus.FAILED,
                fixture.run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(StageAttemptStatus.FAILED,
                fixture.run.stage(HarnessStage.ANALYSIS).currentAttempt().getStatus());
        verify(runRepository).update(fixture.run);
    }

    private Fixture startingFixture() {
        HarnessRun run = HarnessRun.create("run-1", "M3", "/workspace", "CODEX", "test",
                "harness@1.0.0", "admin", "create-1", StageContract.mvpDefaults(), NOW);
        run.startStage(HarnessStage.ANALYSIS, "start-1", NOW.plusSeconds(1));
        CapabilitySnapshotReference snapshot = new CapabilitySnapshotReference("run-1",
                HarnessStage.ANALYSIS, 1, hash('a'), hash('b'), Collections.singleton("reader"));
        ExecutionPermit permit = run.authorizeExecution(HarnessStage.ANALYSIS, snapshot);
        RuntimeExecution execution = RuntimeExecution.prepare("exec-1", "launch-1", permit,
                AgentRuntime.CODEX, NOW.plusSeconds(2));
        run.bindExecution(execution.reference(), NOW.plusSeconds(2));
        execution.markStarting(NOW.plusSeconds(3));
        return new Fixture(run, execution);
    }

    private Fixture runningFixture() {
        Fixture fixture = startingFixture();
        fixture.execution.apply(RuntimeExecutionSignal.started(1L, "codex-test", "process",
                NOW.plusSeconds(4)));
        return fixture;
    }

    private void stub(Fixture fixture) {
        when(executionRepository.findById("exec-1")).thenReturn(Optional.of(fixture.execution));
        when(runRepository.findById("run-1")).thenReturn(Optional.of(fixture.run));
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }

    private static final class Fixture {

        private final HarnessRun run;
        private final RuntimeExecution execution;

        private Fixture(HarnessRun run, RuntimeExecution execution) {
            this.run = run;
            this.execution = execution;
        }
    }
}
