package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 补充输入、批准上游输入和部署动作批准的聚合不变量测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class HarnessRunM4Test {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @Test
    void blocking_question_should_pause_and_resume_the_same_attempt() {
        HarnessRun run = newRun();
        run.startStage(HarnessStage.ANALYSIS, "start-analysis", "admin", NOW.plusSeconds(1));

        assertTrue(run.requestInput(HarnessStage.ANALYSIS, "question-1",
                "Which tenant owns the data?", true, "agent", NOW.plusSeconds(2)));
        assertFalse(run.requestInput(HarnessStage.ANALYSIS, "question-1",
                "Which tenant owns the data?", true, "agent", NOW.plusSeconds(3)));
        assertEquals(HarnessRunStatus.WAITING_INPUT, run.getStatus());
        assertEquals(StageStatus.WAITING_INPUT, run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(StageAttemptStatus.WAITING_INPUT,
                run.stage(HarnessStage.ANALYSIS).currentAttempt().getStatus());
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.submitForApproval(HarnessStage.ANALYSIS, "admin", NOW.plusSeconds(4)));

        assertTrue(run.answerQuestion("question-1", "tenant-a", "admin", NOW.plusSeconds(5)));
        assertFalse(run.answerQuestion("question-1", "tenant-a", "admin", NOW.plusSeconds(6)));
        assertEquals(HarnessRunStatus.ACTIVE, run.getStatus());
        assertEquals(StageStatus.RUNNING, run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(1, run.getQuestions().size());
        assertEquals("tenant-a", run.getQuestions().get(0).getAnswer());
        assertEquals(1, run.stage(HarnessStage.ANALYSIS).getAttempts().size());
    }

    @Test
    void original_requirement_and_workspace_baseline_should_be_fixed_at_creation() {
        WorkspaceBaseline baseline = WorkspaceBaseline.capture(
                "/workspace", "feat/m4", "0123456789012345678901234567890123456789",
                false, hash('a'), NOW);
        HarnessRun run = HarnessRun.create("run-baseline", "M4", "/workspace", "CODEX",
                "local", "harness@1.0.0", "admin", "baseline-key", baseline,
                StageContract.mvpDefaults(), NOW);
        ArtifactContent original = ArtifactContent.from(
                "Implement M4".getBytes(StandardCharsets.UTF_8));

        ArtifactDescriptor descriptor = run.registerOriginalRequirement(
                "original-requirement", original, "admin", NOW);

        assertEquals("feat/m4", run.getWorkspaceBaseline().getBranch());
        assertFalse(run.getWorkspaceBaseline().isClean());
        assertEquals(ArtifactType.ORIGINAL_REQUIREMENT, descriptor.getArtifactType());
        assertEquals(1, descriptor.getAttempt());
        assertEquals(descriptor, run.approvedInputArtifacts(HarnessStage.ANALYSIS).get(0));
        assertEquals(descriptor.getArtifactId(),
                run.artifactSourceReferences(HarnessStage.ANALYSIS).get(0).getArtifactId());
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.registerOriginalRequirement("another", original, "admin", NOW));
    }

    @Test
    void only_approved_upstream_artifacts_should_be_exposed_as_design_inputs() {
        HarnessRun run = newRun();
        run.startStage(HarnessStage.ANALYSIS, "start-analysis", NOW.plusSeconds(1));
        registerRequiredArtifacts(run, HarnessStage.ANALYSIS, 2L);

        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.approvedInputArtifacts(HarnessStage.DESIGN));

        passGatesAndApprove(run, HarnessStage.ANALYSIS, 30L);
        List<ArtifactDescriptor> inputs = run.approvedInputArtifacts(HarnessStage.DESIGN);

        assertEquals(3, inputs.size());
        assertTrue(inputs.stream().allMatch(item -> item.getStage() == HarnessStage.ANALYSIS));
        assertTrue(inputs.stream().noneMatch(item -> item.getArtifactType() == ArtifactType.OPEN_QUESTIONS));
        assertTrue(inputs.stream().allMatch(item -> item.getVersion() == 1));
    }

    @Test
    void artifactRevisionsShouldIncreaseByStageAndTypeAndExposeOnlyLatestContent() {
        HarnessRun run = newRun();
        run.startStage(HarnessStage.ANALYSIS, "start-analysis", NOW.plusSeconds(1));
        ArtifactContent first = ArtifactContent.from("# REQ-1 first".getBytes(StandardCharsets.UTF_8));
        ArtifactContent second = ArtifactContent.from("# REQ-1 revised".getBytes(StandardCharsets.UTF_8));

        ArtifactDescriptor versionOne = run.registerArtifact(HarnessStage.ANALYSIS,
                "generated-1", ArtifactType.REQUIREMENT, first, "text/markdown",
                ArtifactClassification.INTERNAL, "agent",
                Collections.<ArtifactReference>emptyList(), NOW.plusSeconds(2));
        ArtifactDescriptor versionTwo = run.registerArtifact(HarnessStage.ANALYSIS,
                "generated-2", ArtifactType.REQUIREMENT, second, "text/markdown",
                ArtifactClassification.INTERNAL, "agent",
                Collections.<ArtifactReference>emptyList(), NOW.plusSeconds(3));

        assertEquals(1, versionOne.getVersion());
        assertEquals(2, versionTwo.getVersion());
        ArtifactDescriptor current = run.gateArtifactDescriptors(HarnessStage.ANALYSIS).stream()
                .filter(item -> item.getArtifactType() == ArtifactType.REQUIREMENT)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(versionTwo.getArtifactId(), current.getArtifactId());
        assertEquals(second.getSha256(), current.getSha256());
    }

    @Test
    void local_deployment_should_require_an_independent_approval_bound_to_input_hash() {
        HarnessRun run = newRun();
        passStage(run, HarnessStage.ANALYSIS, 1L);
        passStage(run, HarnessStage.DESIGN, 100L);
        passStage(run, HarnessStage.IMPLEMENTATION, 200L);
        run.startStage(HarnessStage.DEPLOYMENT, "start-deployment", NOW.plusSeconds(300));
        String deploymentInputHash = run.approvedInputBaselineHash(HarnessStage.DEPLOYMENT);
        WorkspaceBaseline deployBaseline = WorkspaceBaseline.capture(
                "/workspace", "feat/m4", "0123456789012345678901234567890123456789",
                false, hash('e'), NOW.plusSeconds(300));

        assertFalse(run.hasDeploymentApproval(deploymentInputHash));
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.authorizeDeployment(deployBaseline));
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.approveDeployment("deploy-approval", hash('f'), "admin",
                        "deploy local", NOW.plusSeconds(301)));

        assertTrue(run.approveDeployment("deploy-approval", deploymentInputHash, "admin",
                "deploy local", NOW.plusSeconds(302)));
        assertFalse(run.approveDeployment("deploy-approval", deploymentInputHash, "admin",
                "deploy local", NOW.plusSeconds(303)));
        assertTrue(run.hasDeploymentApproval(deploymentInputHash));
        DeploymentPermit permit = run.authorizeDeployment(deployBaseline);
        assertEquals(deploymentInputHash, permit.getApprovedInputBaselineHash());
        assertEquals(deployBaseline.getDiffHash(), permit.getWorkspaceBaseline().getDiffHash());
        assertEquals("LOCAL_DEPLOY", run.getApprovals().get(run.getApprovals().size() - 1)
                .getApprovalType());
    }

    @Test
    void deploymentReadinessShouldExposeDomainOwnedInputHashAndIndependentApproval() {
        HarnessRun run = newRun();
        passStage(run, HarnessStage.ANALYSIS, 1L);
        passStage(run, HarnessStage.DESIGN, 100L);
        passStage(run, HarnessStage.IMPLEMENTATION, 200L);
        run.startStage(HarnessStage.DEPLOYMENT, "start-deployment", NOW.plusSeconds(300));

        DeploymentReadiness beforeApproval = run.deploymentReadiness();

        assertEquals("run-1", beforeApproval.getRunId());
        assertEquals("local", beforeApproval.getEnvironment());
        assertEquals(1, beforeApproval.getAttemptNumber());
        assertEquals(run.approvedInputBaselineHash(HarnessStage.DEPLOYMENT),
                beforeApproval.getInputBaselineHash());
        assertFalse(beforeApproval.isApproved());

        run.approveDeployment("deploy-approval", beforeApproval.getInputBaselineHash(),
                "admin", "deploy local", NOW.plusSeconds(301));

        assertTrue(run.deploymentReadiness().isApproved());
    }

    private HarnessRun newRun() {
        WorkspaceBaseline baseline = WorkspaceBaseline.capture(
                "/workspace", "feat/m4", "0123456789012345678901234567890123456789",
                false, hash('0'), NOW);
        return HarnessRun.create("run-1", "M4", "/workspace", "CODEX", "local",
                "harness@1.0.0", "admin", "create-key", baseline,
                StageContract.mvpDefaults(), NOW);
    }

    private void passStage(HarnessRun run, HarnessStage stage, long offset) {
        run.startStage(stage, "start-" + stage, NOW.plusSeconds(offset));
        registerRequiredArtifacts(run, stage, offset + 1L);
        passGatesAndApprove(run, stage, offset + 50L);
    }

    private void passGatesAndApprove(HarnessRun run, HarnessStage stage, long offset) {
        for (String rule : run.stage(stage).getContract().getDeterministicGates()) {
            run.recordGate(stage, "gate-" + stage + '-' + rule, rule, true,
                    Collections.<String>emptyList(), null, "gate", NOW.plusSeconds(offset));
        }
        run.submitForApproval(stage, "admin", NOW.plusSeconds(offset + 1L));
        run.approve(stage, "stage-approval-" + stage,
                run.currentArtifactBaselineHash(stage), "admin", "approved",
                NOW.plusSeconds(offset + 2L));
    }

    private void registerRequiredArtifacts(HarnessRun run, HarnessStage stage, long offset) {
        int index = 0;
        for (ArtifactType type : run.stage(stage).getContract().getRequiredOutputArtifacts()) {
            run.registerArtifact(stage, "artifact-" + stage + '-' + type, type,
                    ArtifactContent.from((stage + "-" + type).getBytes(StandardCharsets.UTF_8)),
                    "text/markdown", ArtifactClassification.INTERNAL, "agent",
                    Collections.<ArtifactReference>emptyList(), NOW.plusSeconds(offset + index));
            index++;
        }
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
