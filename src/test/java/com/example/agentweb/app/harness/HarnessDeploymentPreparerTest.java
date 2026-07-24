package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.DeploymentArtifactFactory;
import com.example.agentweb.domain.harness.DeploymentCommand;
import com.example.agentweb.domain.harness.DeploymentCommandTemplate;
import com.example.agentweb.domain.harness.DeploymentCommandTemplateCatalog;
import com.example.agentweb.domain.harness.DeploymentExecution;
import com.example.agentweb.domain.harness.DeploymentExecutionRepository;
import com.example.agentweb.domain.harness.DeploymentOutcome;
import com.example.agentweb.domain.harness.DeploymentStep;
import com.example.agentweb.domain.harness.DeploymentStepResult;
import com.example.agentweb.domain.harness.DeploymentPermit;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.IllegalHarnessTransitionException;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
 * 部署准备的独立 Approval、幂等与 PREPARED 持久化测试。
 *
 * @author alex
 * @since 2026-07-23
 */
@ExtendWith(MockitoExtension.class)
class HarnessDeploymentPreparerTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Mock
    private HarnessRunRepository runRepository;
    @Mock
    private DeploymentExecutionRepository executionRepository;
    @Mock
    private DeploymentCommandTemplateCatalog templateCatalog;
    @Mock
    private WorkspaceBaselineGateway baselineGateway;
    @Mock
    private ArtifactStore artifactStore;

    private HarnessDeploymentPreparer preparer;

    @BeforeEach
    void setUp() {
        preparer = new HarnessDeploymentPreparer(runRepository, executionRepository,
                templateCatalog, baselineGateway, artifactStore,
                (contentType, content) -> content, () -> "deploy-1",
                new DeploymentArtifactFactory(),
                Clock.fixed(NOW.plusSeconds(500), ZoneOffset.UTC));
    }

    @Test
    void missingIndependentApprovalShouldNotCreateDeploymentExecution() {
        HarnessRun run = deploymentRun(false);
        String inputHash = run.approvedInputBaselineHash(HarnessStage.DEPLOYMENT);
        stubPreparation(run);

        assertThrows(IllegalHarnessTransitionException.class, () -> preparer.prepare(
                new StartHarnessDeploymentCommand("run-1", "local-default", inputHash, "key-1")));

        verify(executionRepository, never()).add(any(DeploymentExecution.class));
        verify(runRepository, never()).update(any(HarnessRun.class));
    }

    @Test
    void approvedRequestShouldPersistPreparedExecutionAndRunAudit() {
        HarnessRun run = deploymentRun(true);
        String inputHash = run.approvedInputBaselineHash(HarnessStage.DEPLOYMENT);
        stubPreparation(run);

        PreparedHarnessDeployment prepared = preparer.prepare(
                new StartHarnessDeploymentCommand("run-1", "local-default", inputHash, "key-1"));

        assertEquals("deploy-1", prepared.getSpec().getExecutionId());
        verify(executionRepository).add(any(DeploymentExecution.class));
        verify(runRepository).update(run);
    }

    @Test
    void completeShouldReadApprovedEvidenceStoreArtifactsBeforeUpdatingRun() {
        HarnessRun run = deploymentRun(true);
        DeploymentExecution execution = DeploymentExecution.prepare("deploy-1", "key-1",
                new DeploymentPermit("run-1", 1,
                        run.approvedInputBaselineHash(HarnessStage.DEPLOYMENT), baseline()),
                template().reference(), NOW.plusSeconds(450));
        execution.begin(baseline(), NOW.plusSeconds(451));
        when(executionRepository.findById("deploy-1")).thenReturn(Optional.of(execution));
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(artifactStore.read(any())).thenAnswer(invocation -> {
            com.example.agentweb.domain.harness.ArtifactDescriptor descriptor =
                    invocation.getArgument(0);
            return ArtifactContent.from(artifactBody(
                    descriptor.getStage(), descriptor.getArtifactType())
                    .getBytes(StandardCharsets.UTF_8));
        });

        preparer.complete("deploy-1", successfulOutcome());

        assertEquals(5L, run.getArtifacts().stream()
                .filter(item -> item.getStage() == HarnessStage.DEPLOYMENT).count());
        assertTrue(run.getArtifacts().stream()
                .filter(item -> item.getStage() == HarnessStage.DEPLOYMENT)
                .map(item -> item.getArtifactType())
                .collect(Collectors.toSet())
                .containsAll(Arrays.asList(ArtifactType.PREFLIGHT, ArtifactType.BUILD_EVIDENCE,
                        ArtifactType.DEPLOYMENT_RECORD, ArtifactType.ACCEPTANCE_RESULT,
                        ArtifactType.FINAL_REPORT)));
        verify(artifactStore, times(run.deliveryReportArtifactDescriptors().size())).read(any());
        InOrder persistence = inOrder(artifactStore, executionRepository, runRepository);
        persistence.verify(artifactStore, times(5)).store(any(), any());
        persistence.verify(executionRepository).update(execution);
        persistence.verify(runRepository).update(run);
    }

    private void stubPreparation(HarnessRun run) {
        when(templateCatalog.resolve("local-default")).thenReturn(template());
        when(executionRepository.findByIdempotencyKey("run-1", "key-1"))
                .thenReturn(Optional.empty());
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(baselineGateway.capture("/workspace")).thenReturn(baseline());
    }

    private HarnessRun deploymentRun(boolean approveDeployment) {
        HarnessRun run = HarnessRun.create("run-1", "M4", "/workspace", "CODEX", "local",
                "harness@1", "admin", "create", baseline(), StageContract.mvpDefaults(), NOW);
        long offset = 1L;
        for (HarnessStage stage : Arrays.asList(HarnessStage.ANALYSIS, HarnessStage.DESIGN,
                HarnessStage.IMPLEMENTATION)) {
            run.startStage(stage, "start-" + stage, NOW.plusSeconds(offset));
            int index = 0;
            for (ArtifactType type : run.stage(stage).getContract().getRequiredOutputArtifacts()) {
                String body = artifactBody(stage, type);
                run.registerArtifact(stage, stage + "-" + type, type,
                        ArtifactContent.from(body.getBytes(StandardCharsets.UTF_8)),
                        body.startsWith("{") ? "application/json" : "text/markdown",
                        ArtifactClassification.INTERNAL, "agent",
                        Collections.<ArtifactReference>emptyList(),
                        NOW.plusSeconds(offset + 1 + index++));
            }
            long gateTime = offset + 20;
            for (String rule : run.stage(stage).getContract().getDeterministicGates()) {
                run.recordGate(stage, stage + rule, rule, true, Collections.emptyList(),
                        null, "gate", NOW.plusSeconds(gateTime));
            }
            run.submitForApproval(stage, "admin", NOW.plusSeconds(gateTime + 1));
            run.approve(stage, "approval-" + stage, run.currentArtifactBaselineHash(stage),
                    "admin", "ok", NOW.plusSeconds(gateTime + 2));
            offset += 100;
        }
        run.startStage(HarnessStage.DEPLOYMENT, "start-deployment", NOW.plusSeconds(400));
        if (approveDeployment) {
            run.approveDeployment("deploy-approval",
                    run.approvedInputBaselineHash(HarnessStage.DEPLOYMENT),
                    "admin", "deploy", NOW.plusSeconds(401));
        }
        return run;
    }

    private String artifactBody(HarnessStage stage, ArtifactType type) {
        if (type == ArtifactType.REQUIREMENT) {
            return "# REQ-1 Deliver Harness M4";
        }
        if (type == ArtifactType.ACCEPTANCE_CRITERIA) {
            return "{\"acceptanceCriteria\":[{\"id\":\"AC-1\",\"requirementId\":\"REQ-1\","
                    + "\"description\":\"local flow succeeds\","
                    + "\"verification\":\"acceptance command\"}]}";
        }
        if (type == ArtifactType.TRACEABILITY && stage == HarnessStage.DESIGN) {
            return "{\"links\":[{\"requirementId\":\"REQ-1\","
                    + "\"acceptanceCriteriaId\":\"AC-1\","
                    + "\"designRef\":\"SOLUTION#domain\",\"testRef\":\"RuleTest\"}]}";
        }
        if (type == ArtifactType.TRACEABILITY && stage == HarnessStage.IMPLEMENTATION) {
            return "{\"links\":[{\"requirementId\":\"REQ-1\","
                    + "\"acceptanceCriteriaId\":\"AC-1\","
                    + "\"designRef\":\"SOLUTION#domain\",\"testRef\":\"RuleTest\","
                    + "\"implementationRef\":\"src/main/A.java\"}]}";
        }
        if (type == ArtifactType.TEST_EVIDENCE) {
            return "{\"commands\":[{\"command\":\"mvn RuleTest\",\"phase\":\"GREEN\","
                    + "\"exitCode\":0,\"runtimeObserved\":true}]}";
        }
        if (type == ArtifactType.CHANGED_FILES) {
            return "{\"files\":[{\"path\":\"src/main/A.java\",\"sensitive\":false}]}";
        }
        return type.name();
    }

    private DeploymentOutcome successfulOutcome() {
        java.util.List<DeploymentStepResult> results =
                new java.util.ArrayList<DeploymentStepResult>();
        int offset = 452;
        for (DeploymentStep step : Arrays.asList(DeploymentStep.BUILD, DeploymentStep.DEPLOY,
                DeploymentStep.HEALTH_CHECK, DeploymentStep.ACCEPTANCE)) {
            results.add(new DeploymentStepResult(step, 0, hash('d'), "ok",
                    NOW.plusSeconds(offset), NOW.plusSeconds(offset + 1)));
            offset += 2;
        }
        return new DeploymentOutcome(results);
    }

    private WorkspaceBaseline baseline() {
        return WorkspaceBaseline.capture("/workspace", "feat/m4",
                "0123456789012345678901234567890123456789", false, hash('a'), NOW);
    }

    private DeploymentCommandTemplate template() {
        Map<DeploymentStep, DeploymentCommand> commands =
                new EnumMap<DeploymentStep, DeploymentCommand>(DeploymentStep.class);
        for (DeploymentStep step : DeploymentStep.values()) {
            commands.put(step, new DeploymentCommand(step, Arrays.asList("runner", step.name())));
        }
        return new DeploymentCommandTemplate("local-default", "1", "local", commands);
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
