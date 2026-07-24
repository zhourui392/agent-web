package com.example.agentweb.app.harness;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.DuplicateHarnessRunException;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.HarnessDeterministicGatePolicy;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Harness Application 层只验证 Repository、Store、路径端口和聚合的编排。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(MockitoExtension.class)
class HarnessAppServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");

    @Mock
    private HarnessRunRepository repository;
    @Mock
    private ArtifactStore artifactStore;
    @Mock
    private WorkspacePathPolicy workspacePathPolicy;
    @Mock
    private WorkspaceBaselineGateway workspaceBaselineGateway;

    private HarnessAppServiceImpl service;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        UserContext userContext = () -> Optional.of(new LoginUser("admin", "Admin", null));
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(userContext);
        HarnessIdGenerator idGenerator = new HarnessIdGenerator() {
            private int value;

            @Override
            public String nextId() {
                value++;
                return "generated-" + value;
            }
        };
        service = new HarnessAppServiceImpl(repository, artifactStore,
                (contentType, content) -> content, workspacePathPolicy,
                workspaceBaselineGateway,
                currentUserProvider, idGenerator, new HarnessDeterministicGatePolicy(),
                clock);
    }

    @Test
    void create_should_validate_workspace_then_persist_new_aggregate() {
        when(workspacePathPolicy.requireExistingDirectory("/requested"))
                .thenReturn("/real/workspace");
        when(repository.findByCreatorAndIdempotencyKey("admin", "create-key"))
                .thenReturn(Optional.<HarnessRun>empty());
        when(workspaceBaselineGateway.capture("/real/workspace")).thenReturn(WorkspaceBaseline.capture(
                "/real/workspace", "master", "0123456789012345678901234567890123456789",
                true, String.join("", Collections.nCopies(64, "0")), NOW));

        HarnessMutationResult result = service.create(new CreateHarnessRunCommand(
                "M1", "/requested", "CODEX", "local", "harness@1.0.0", "create-key",
                "Implement M4"));

        assertEquals("generated-1", result.getRunId());
        assertEquals("DRAFT", result.getStatus());
        assertTrue(!result.isDuplicated());
        verify(workspacePathPolicy).requireExistingDirectory("/requested");
        verify(workspaceBaselineGateway).capture("/real/workspace");
        verify(artifactStore).store(any(), any());
        verify(repository).add(any(HarnessRun.class));
    }

    @Test
    void create_duplicate_should_return_same_run_but_reject_different_request() {
        HarnessRun existing = newRun();
        when(repository.findByCreatorAndIdempotencyKey("admin", "create-key"))
                .thenReturn(Optional.of(existing));
        when(workspacePathPolicy.requireExistingDirectory("/requested"))
                .thenReturn("/workspace");

        HarnessMutationResult duplicate = service.create(new CreateHarnessRunCommand(
                "M1", "/requested", "CODEX", "local", "harness@1.0.0", "create-key",
                "Implement M4"));
        assertTrue(duplicate.isDuplicated());
        verify(repository, never()).add(any(HarnessRun.class));

        assertThrows(DuplicateHarnessRunException.class, () -> service.create(
                new CreateHarnessRunCommand("different", "/requested", "CODEX", "local",
                        "harness@1.0.0", "create-key", "Implement M4")));
    }

    @Test
    void start_should_delegate_state_rule_to_aggregate_and_persist_only_changed_command() {
        HarnessRun run = newRun();
        when(repository.findById("run-1")).thenReturn(Optional.of(run));

        service.startStage("run-1", HarnessStage.ANALYSIS, "start-key");
        service.startStage("run-1", HarnessStage.ANALYSIS, "start-key");

        assertEquals(1, run.stage(HarnessStage.ANALYSIS).getAttempts().size());
        verify(repository, times(1)).update(run);
    }

    @Test
    void register_artifact_should_store_body_before_persisting_metadata() {
        HarnessRun run = newRun();
        run.startStage(HarnessStage.ANALYSIS, "start", NOW);
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        ArtifactContent content = ArtifactContent.from("requirement".getBytes(StandardCharsets.UTF_8));

        service.registerArtifact(new RegisterHarnessArtifactCommand(
                "run-1", HarnessStage.ANALYSIS, ArtifactType.REQUIREMENT,
                content.copyBytes(), "text/markdown", ArtifactClassification.INTERNAL,
                Collections.<ArtifactReference>emptyList()));

        InOrder order = inOrder(artifactStore, repository);
        order.verify(artifactStore).store(any(), any());
        order.verify(repository).update(run);
        assertEquals(1, run.getArtifacts().size());
    }

    @Test
    void missing_run_should_map_to_domain_not_found_without_side_effect() {
        when(repository.findById("missing")).thenReturn(Optional.<HarnessRun>empty());

        assertThrows(HarnessRunNotFoundException.class,
                () -> service.startStage("missing", HarnessStage.ANALYSIS, "start-key"));
        verify(repository, never()).update(any(HarnessRun.class));
    }

    @Test
    void evaluate_gate_should_read_current_artifacts_and_persist_server_decision() {
        HarnessRun run = newRun();
        run.startStage(HarnessStage.ANALYSIS, "start", NOW);
        register(run, ArtifactType.REQUIREMENT, "# REQ-1 Create run", "text/markdown");
        register(run, ArtifactType.ACCEPTANCE_CRITERIA,
                "{\"acceptanceCriteria\":[{\"id\":\"AC-1\",\"requirementId\":\"REQ-1\","
                        + "\"description\":\"created\",\"verification\":\"HTTP 201\"}]}",
                "application/json");
        register(run, ArtifactType.IMPACT_ANALYSIS, "harness", "text/markdown");
        register(run, ArtifactType.OPEN_QUESTIONS, "{\"questions\":[]}", "application/json");
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        for (com.example.agentweb.domain.harness.ArtifactDescriptor descriptor : run.getArtifacts()) {
            when(artifactStore.read(descriptor)).thenReturn(ArtifactContent.from(
                    contentFor(descriptor.getArtifactType()).getBytes(StandardCharsets.UTF_8)));
        }

        service.evaluateGate("run-1", HarnessStage.ANALYSIS, "artifact-schema-valid");

        assertEquals(1, run.getGateResults().size());
        assertTrue(run.getGateResults().get(0).isPassed());
        verify(repository).update(run);
    }

    @Test
    void question_answer_and_deployment_approval_should_only_orchestrate_aggregate() {
        HarnessRun run = newRun();
        run.startStage(HarnessStage.ANALYSIS, "start", NOW);
        when(repository.findById("run-1")).thenReturn(Optional.of(run));

        service.requestInput("run-1", HarnessStage.ANALYSIS, "question-1",
                "Which tenant?", true);
        service.answerQuestion("run-1", "question-1", "tenant-a");

        assertEquals("tenant-a", run.getQuestions().get(0).getAnswer());
        verify(repository, times(2)).update(run);
    }

    @Test
    void implementation_start_and_retry_should_store_distinct_attempt_baselines() {
        HarnessRun run = runReadyForImplementation();
        WorkspaceBaseline first = baseline('1');
        WorkspaceBaseline second = baseline('2');
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        when(workspaceBaselineGateway.capture("/workspace")).thenReturn(first, second);

        service.startStage("run-1", HarnessStage.IMPLEMENTATION, "implementation-1");
        registerImplementationOutputs(run, 0L);
        approveCurrentStage(run, HarnessStage.IMPLEMENTATION, 0L);
        clock.advanceSeconds(10L);
        service.retryStage("run-1", HarnessStage.IMPLEMENTATION, "implementation-2");

        List<ArtifactDescriptor> baselines = run.artifactVersions(ArtifactType.CHANGED_FILES);
        assertEquals(2, baselines.size());
        assertEquals(1, baselines.get(0).getAttempt());
        assertEquals(2, baselines.get(1).getAttempt());
        assertTrue(!baselines.get(0).getSha256().equals(baselines.get(1).getSha256()));
        verify(artifactStore, times(2)).store(any(), any());
        verify(repository, times(2)).update(run);
    }

    private void register(HarnessRun run, ArtifactType type, String value, String contentType) {
        run.registerArtifact(HarnessStage.ANALYSIS, "artifact-" + type, type,
                ArtifactContent.from(value.getBytes(StandardCharsets.UTF_8)), contentType,
                ArtifactClassification.INTERNAL, "agent", Collections.<ArtifactReference>emptyList(), NOW);
    }

    private String contentFor(ArtifactType type) {
        switch (type) {
            case REQUIREMENT:
                return "# REQ-1 Create run";
            case ACCEPTANCE_CRITERIA:
                return "{\"acceptanceCriteria\":[{\"id\":\"AC-1\",\"requirementId\":\"REQ-1\","
                        + "\"description\":\"created\",\"verification\":\"HTTP 201\"}]}";
            case IMPACT_ANALYSIS:
                return "harness";
            case OPEN_QUESTIONS:
                return "{\"questions\":[]}";
            default:
                throw new IllegalArgumentException("unexpected type " + type);
        }
    }

    private HarnessRun newRun() {
        return HarnessRun.create("run-1", "M1", "/workspace", "CODEX", "local",
                "harness@1.0.0", "admin", "create-key", StageContract.mvpDefaults(), NOW);
    }

    private HarnessRun runReadyForImplementation() {
        HarnessRun run = HarnessRun.create("run-1", "M1", "/workspace", "CODEX", "local",
                "harness@1.0.0", "admin", "create-key", StageContract.mvpDefaults(),
                NOW.minusSeconds(1000L));
        passStage(run, HarnessStage.ANALYSIS, -200L);
        passStage(run, HarnessStage.DESIGN, -100L);
        return run;
    }

    private void passStage(HarnessRun run, HarnessStage stage, long offset) {
        run.startStage(stage, "start-" + stage, NOW.plusSeconds(offset));
        for (ArtifactType type : run.stage(stage).getContract().getRequiredOutputArtifacts()) {
            run.registerArtifact(stage, "artifact-" + stage + '-' + type, type,
                    ArtifactContent.from((stage + "-" + type).getBytes(StandardCharsets.UTF_8)),
                    "text/markdown", ArtifactClassification.INTERNAL, "agent",
                    Collections.<ArtifactReference>emptyList(), NOW.plusSeconds(offset + 1L));
        }
        approveCurrentStage(run, stage, offset + 20L);
    }

    private void registerImplementationOutputs(HarnessRun run, long offset) {
        ArtifactType[] types = {ArtifactType.TEST_EVIDENCE,
                ArtifactType.IMPLEMENTATION_SUMMARY, ArtifactType.TRACEABILITY};
        for (ArtifactType type : types) {
            run.registerArtifact(HarnessStage.IMPLEMENTATION,
                    "artifact-IMPLEMENTATION-" + type, type,
                    ArtifactContent.from((HarnessStage.IMPLEMENTATION + "-" + type)
                            .getBytes(StandardCharsets.UTF_8)),
                    "text/markdown", ArtifactClassification.INTERNAL, "agent",
                    Collections.<ArtifactReference>emptyList(), NOW.plusSeconds(offset));
        }
    }

    private void approveCurrentStage(HarnessRun run, HarnessStage stage, long offset) {
        for (String rule : run.stage(stage).getContract().getDeterministicGates()) {
            run.recordGate(stage, "gate-" + stage + '-' + rule, rule, true,
                    Collections.<String>emptyList(), null, "agent", NOW.plusSeconds(offset + 1L));
        }
        run.submitForApproval(stage, "admin", NOW.plusSeconds(offset + 2L));
        run.approve(stage, "approval-" + stage,
                run.currentArtifactBaselineHash(stage), "admin", "approved",
                NOW.plusSeconds(offset + 3L));
    }

    private WorkspaceBaseline baseline(char diff) {
        return WorkspaceBaseline.capture("/workspace", "UNKNOWN",
                "0123456789012345678901234567890123456789", false,
                String.join("", Collections.nCopies(64, String.valueOf(diff))), NOW);
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
