package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.RuntimeEvent;
import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.CapabilitySnapshotReference;
import com.example.agentweb.domain.harness.ChangedFileEvidence;
import com.example.agentweb.domain.harness.ExecutionPermit;
import com.example.agentweb.domain.harness.HarnessGeneratedArtifact;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessRunStatus;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.IllegalHarnessTransitionException;
import com.example.agentweb.domain.harness.ImplementationEvidenceFactory;
import com.example.agentweb.domain.harness.ImplementationCommandEvidenceFactory;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionEvent;
import com.example.agentweb.domain.harness.RuntimeExecutionRepository;
import com.example.agentweb.domain.harness.RuntimeExecutionSignal;
import com.example.agentweb.domain.harness.RuntimeExecutionStatus;
import com.example.agentweb.domain.harness.RuntimeArtifactBundle;
import com.example.agentweb.domain.harness.RuntimeCommandObservation;
import com.example.agentweb.domain.harness.RuntimeProducedArtifact;
import com.example.agentweb.domain.harness.StageAttemptStatus;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.StageStatus;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import com.example.agentweb.domain.harness.WorkspaceChangeEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    @Mock
    private ArtifactStore artifactStore;
    @Mock
    private WorkspaceBaselineGateway workspaceBaselineGateway;

    private HarnessRuntimeEventService service;

    @BeforeEach
    void setUp() {
        service = new HarnessRuntimeEventService(executionRepository, runRepository, artifactStore,
                (contentType, content) -> content,
                workspaceBaselineGateway,
                new ImplementationEvidenceFactory(),
                new ImplementationCommandEvidenceFactory(),
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
    void succeededExecutionShouldRegisterBundleWithoutMarkingStagePassed() {
        Fixture fixture = runningFixture();
        stub(fixture);

        service.onEvent(new RuntimeEvent("exec-1", RuntimeExecutionSignal.succeeded(
                2L, 0, "artifact:result", true, NOW.plusSeconds(5)), "succeeded", bundle()));

        assertEquals(RuntimeExecutionStatus.SUCCEEDED, fixture.execution.getStatus());
        assertEquals(HarnessRunStatus.ACTIVE, fixture.run.getStatus());
        assertEquals(StageStatus.RUNNING,
                fixture.run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(StageAttemptStatus.RUNNING,
                fixture.run.stage(HarnessStage.ANALYSIS).currentAttempt().getStatus());
        assertEquals(4, fixture.run.gateArtifactDescriptors(HarnessStage.ANALYSIS).size());
        verify(artifactStore, org.mockito.Mockito.times(4)).store(any(), any());
        verify(runRepository).update(fixture.run);
    }

    @Test
    void succeededExecutionWithoutBundleShouldFailRunExplicitly() {
        Fixture fixture = runningFixture();
        stub(fixture);

        service.onEvent(new RuntimeEvent("exec-1", RuntimeExecutionSignal.succeeded(
                2L, 0, "artifact:result", true, NOW.plusSeconds(5)), "succeeded"));

        assertEquals(RuntimeExecutionStatus.FAILED, fixture.execution.getStatus());
        assertEquals(HarnessRunStatus.FAILED, fixture.run.getStatus());
        assertEquals(StageStatus.FAILED,
                fixture.run.stage(HarnessStage.ANALYSIS).getStatus());
        verify(artifactStore, never()).store(any(), any());
    }

    @Test
    void implementationSuccessShouldReplaceAgentClaimWithWorkspaceDifference() throws Exception {
        Fixture fixture = runningImplementationFixture();
        WorkspaceBaseline baseline = new ImplementationEvidenceFactory()
                .readBaseline(fixture.baseline.getContent());
        WorkspaceBaseline current = WorkspaceBaseline.capture("/workspace", "feat/m4",
                baseline.getHead(), false, hash('c'), Arrays.asList(
                        baseline.getFiles().get(0),
                        new ChangedFileEvidence("data/secrets.properties", "??", hash('d'), true)),
                NOW.plusSeconds(5));
        stub(fixture);
        when(artifactStore.read(fixture.baseline.getDescriptor()))
                .thenReturn(fixture.baseline.getContent());
        when(workspaceBaselineGateway.captureChanges(
                org.mockito.ArgumentMatchers.eq("/workspace"), any(WorkspaceBaseline.class)))
                .thenReturn(new WorkspaceChangeEvidence(baseline, current));

        service.onEvent(new RuntimeEvent("exec-1", RuntimeExecutionSignal.succeeded(
                2L, 0, "artifact:result", true, NOW.plusSeconds(5)), "succeeded",
                implementationBundle(), Arrays.asList(
                        new RuntimeCommandObservation(1, "mvn focused", 1, hash('e')),
                        new RuntimeCommandObservation(2, "mvn focused", 0, hash('f')))));

        ArgumentCaptor<ArtifactDescriptor> descriptors =
                ArgumentCaptor.forClass(ArtifactDescriptor.class);
        ArgumentCaptor<ArtifactContent> contents = ArgumentCaptor.forClass(ArtifactContent.class);
        verify(artifactStore, org.mockito.Mockito.times(4))
                .store(descriptors.capture(), contents.capture());
        ArtifactContent changedFiles = null;
        for (int index = 0; index < descriptors.getAllValues().size(); index++) {
            if (descriptors.getAllValues().get(index).getArtifactType()
                    == ArtifactType.CHANGED_FILES) {
                changedFiles = contents.getAllValues().get(index);
            }
        }
        assertNotNull(changedFiles);
        JsonNode json = new ObjectMapper().readTree(changedFiles.copyBytes());
        assertEquals(1, json.path("files").size());
        assertEquals("data/secrets.properties", json.path("files").get(0).path("path").asText());
        assertEquals(true, json.path("files").get(0).path("sensitive").asBoolean());
        assertEquals(false, json.path("files").toString().contains("agent-claimed.txt"));
        assertEquals(false, json.path("files").toString().contains("user-notes.txt"));
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

    private Fixture runningImplementationFixture() {
        WorkspaceBaseline runBaseline = WorkspaceBaseline.capture(
                "/workspace", "feat/m4", "0123456789012345678901234567890123456789",
                true, hash('0'), NOW.minusSeconds(1000L));
        HarnessRun run = HarnessRun.create("run-1", "M4", "/workspace", "CODEX", "test",
                "harness@1.0.0", "admin", "create-1", runBaseline,
                StageContract.mvpDefaults(), NOW.minusSeconds(1000L));
        passStage(run, HarnessStage.ANALYSIS, NOW.minusSeconds(900L));
        passStage(run, HarnessStage.DESIGN, NOW.minusSeconds(500L));
        run.startStage(HarnessStage.IMPLEMENTATION, "start-implementation", NOW);
        WorkspaceBaseline workspaceBaseline = WorkspaceBaseline.capture(
                "/workspace", "feat/m4", runBaseline.getHead(), false, hash('b'),
                Collections.singletonList(
                        new ChangedFileEvidence("user-notes.txt", "M", hash('1'), false)), NOW);
        HarnessGeneratedArtifact baseline = run.captureImplementationBaseline(
                HarnessStage.IMPLEMENTATION, workspaceBaseline, NOW);
        CapabilitySnapshotReference snapshot = new CapabilitySnapshotReference("run-1",
                HarnessStage.IMPLEMENTATION, 1, hash('a'), hash('b'), Collections.<String>emptySet());
        ExecutionPermit permit = run.authorizeExecution(HarnessStage.IMPLEMENTATION, snapshot);
        RuntimeExecution execution = RuntimeExecution.prepare("exec-1", "launch-1", permit,
                AgentRuntime.CODEX, NOW.plusSeconds(1));
        run.bindExecution(execution.reference(), NOW.plusSeconds(1));
        execution.markStarting(NOW.plusSeconds(2));
        execution.apply(RuntimeExecutionSignal.started(1L, "codex-test", "process",
                NOW.plusSeconds(3)));
        return new Fixture(run, execution, baseline);
    }

    private RuntimeArtifactBundle bundle() {
        return RuntimeArtifactBundle.create(RuntimeArtifactBundle.SCHEMA_VERSION,
                HarnessStage.ANALYSIS, Arrays.asList(
                        artifact("requirements", ArtifactType.REQUIREMENT),
                        artifact("acceptance", ArtifactType.ACCEPTANCE_CRITERIA),
                        artifact("impact", ArtifactType.IMPACT_ANALYSIS),
                        artifact("questions", ArtifactType.OPEN_QUESTIONS)),
                fixtureContractOutputs());
    }

    private RuntimeArtifactBundle implementationBundle() {
        return RuntimeArtifactBundle.create(RuntimeArtifactBundle.SCHEMA_VERSION,
                HarnessStage.IMPLEMENTATION, Arrays.asList(
                        new RuntimeProducedArtifact("claimed", ArtifactType.CHANGED_FILES,
                                "application/json", ArtifactClassification.INTERNAL,
                                ArtifactContent.from(("{\"files\":[{\"path\":"
                                        + "\"agent-claimed.txt\"}]}")
                                        .getBytes(StandardCharsets.UTF_8))),
                        new RuntimeProducedArtifact("tests", ArtifactType.TEST_EVIDENCE,
                                "application/json", ArtifactClassification.INTERNAL,
                                ArtifactContent.from(("{\"commands\":["
                                        + "{\"command\":\"mvn focused\",\"phase\":\"RED\","
                                        + "\"exitCode\":1},{\"command\":\"mvn focused\","
                                        + "\"phase\":\"GREEN\",\"exitCode\":0}]}")
                                        .getBytes(StandardCharsets.UTF_8))),
                        artifact("summary", ArtifactType.IMPLEMENTATION_SUMMARY),
                        artifact("trace", ArtifactType.TRACEABILITY)),
                StageContract.mvpDefaults().get(HarnessStage.IMPLEMENTATION.ordinal())
                        .getRequiredOutputArtifacts());
    }

    private java.util.Set<ArtifactType> fixtureContractOutputs() {
        return StageContract.mvpDefaults().get(0).getRequiredOutputArtifacts();
    }

    private RuntimeProducedArtifact artifact(String id, ArtifactType type) {
        return new RuntimeProducedArtifact(id, type, "application/json",
                ArtifactClassification.INTERNAL,
                ArtifactContent.from("{\"items\":[]}".getBytes(StandardCharsets.UTF_8)));
    }

    private void stub(Fixture fixture) {
        when(executionRepository.findById("exec-1")).thenReturn(Optional.of(fixture.execution));
        when(runRepository.findById("run-1")).thenReturn(Optional.of(fixture.run));
    }

    private void passStage(HarnessRun run, HarnessStage stage, Instant startedAt) {
        run.startStage(stage, "start-" + stage, startedAt);
        int index = 1;
        for (ArtifactType type : run.stage(stage).getContract().getRequiredOutputArtifacts()) {
            run.registerArtifact(stage, "artifact-" + stage + '-' + type, type,
                    ArtifactContent.from((stage + "-" + type).getBytes(StandardCharsets.UTF_8)),
                    "text/markdown", ArtifactClassification.INTERNAL, "agent",
                    Collections.emptyList(), startedAt.plusSeconds(index++));
        }
        for (String rule : run.stage(stage).getContract().getDeterministicGates()) {
            run.recordGate(stage, "gate-" + stage + '-' + rule, rule, true,
                    Collections.emptyList(), null, "agent", startedAt.plusSeconds(index++));
        }
        run.submitForApproval(stage, "admin", startedAt.plusSeconds(index++));
        run.approve(stage, "approval-" + stage, run.currentArtifactBaselineHash(stage),
                "admin", "approved", startedAt.plusSeconds(index));
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }

    private static final class Fixture {

        private final HarnessRun run;
        private final RuntimeExecution execution;
        private final HarnessGeneratedArtifact baseline;

        private Fixture(HarnessRun run, RuntimeExecution execution) {
            this(run, execution, null);
        }

        private Fixture(HarnessRun run, RuntimeExecution execution,
                        HarnessGeneratedArtifact baseline) {
            this.run = run;
            this.execution = execution;
            this.baseline = baseline;
        }
    }
}
