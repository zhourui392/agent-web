package com.example.agentweb.app.requirement;

import com.example.agentweb.adapter.verification.CollectedArtifact;
import com.example.agentweb.adapter.verification.CollectedVerification;
import com.example.agentweb.adapter.verification.VerificationArtifactCollector;
import com.example.agentweb.app.agentrun.PromptAssemblyResult;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.verification.ArtifactStore;
import com.example.agentweb.app.workspace.PortLeaseStore;
import com.example.agentweb.domain.requirement.RequirementStatus;
import com.example.agentweb.domain.verification.VerificationOutcome;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证编排：T7 → run → 采集 → 终态映射（含退出码兜底）；采集失败只降级。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class VerifyRunServiceTest {

    private static final String REQ_ID = "R2607040001";
    private static final String ACTOR = "V33215020";

    private RequirementAppService appService;
    private WorkspaceRepository workspaceRepository;
    private RequirementRunLauncher launcher;
    private VerificationArtifactCollector collector;
    private ArtifactStore artifactStore;
    private VerifyRunService service;
    private RequirementWorkspace workspace;

    @BeforeEach
    public void setUp() {
        appService = mock(RequirementAppService.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        launcher = mock(RequirementRunLauncher.class);
        collector = mock(VerificationArtifactCollector.class);
        artifactStore = mock(ArtifactStore.class);
        PromptAssemblyService promptAssembly = mock(PromptAssemblyService.class);
        PromptAssemblyResult result = mock(PromptAssemblyResult.class);
        when(result.getPrompt()).thenReturn("ASSEMBLED");
        when(promptAssembly.assemble(any())).thenReturn(result);

        workspace = RequirementWorkspace.create(REQ_ID, "http://git/repo.git",
                "D:/ws/mirrors/repo.git", "D:/ws/worktrees/" + REQ_ID, 72);
        workspace.markReady();
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(workspace);

        service = new VerifyRunService(appService, workspaceRepository, mock(PortLeaseStore.class),
                promptAssembly, launcher, collector, artifactStore,
                new RequirementProperties());
    }

    @Test
    public void verified_outcome_should_apply_and_save_artifacts() {
        when(collector.collect(workspace.getWorktreePath())).thenReturn(new CollectedVerification(
                VerificationOutcome.VERIFIED,
                List.of(new CollectedArtifact("FLOWSTATE", "state: SWIMLANE_VERIFIED", null)), null));
        when(appService.applyVerificationOutcome(REQ_ID, VerificationOutcome.VERIFIED, "system:verify"))
                .thenReturn(RequirementStatus.REVIEW);

        service.startVerifyRun(REQ_ID, ACTOR);
        completeRun(new RunResult(0, "raw", ""));

        verify(appService).startVerify(REQ_ID, ACTOR);
        verify(artifactStore).saveAll(eq(REQ_ID), any(), any());
    }

    @Test
    public void missing_evidence_should_fallback_by_exit_code() {
        when(collector.collect(workspace.getWorktreePath())).thenReturn(
                new CollectedVerification(null, List.of(), "flowstate 文件缺失"));
        when(appService.applyVerificationOutcome(REQ_ID, VerificationOutcome.DEPLOY_FAILED, "system:verify"))
                .thenReturn(RequirementStatus.SUSPENDED);

        service.startVerifyRun(REQ_ID, ACTOR);
        completeRun(new RunResult(66, "raw", ""));

        verify(appService).applyVerificationOutcome(REQ_ID, VerificationOutcome.DEPLOY_FAILED, "system:verify");
    }

    @Test
    public void artifact_save_failure_should_not_block_outcome() {
        when(collector.collect(anyString())).thenReturn(
                new CollectedVerification(VerificationOutcome.BLOCKED, List.of(), null));
        doThrow(new IllegalStateException("db down")).when(artifactStore)
                .saveAll(anyString(), any(), any());
        when(appService.applyVerificationOutcome(REQ_ID, VerificationOutcome.BLOCKED, "system:verify"))
                .thenReturn(RequirementStatus.SUSPENDED);

        service.startVerifyRun(REQ_ID, ACTOR);
        assertDoesNotThrow(() -> completeRun(new RunResult(0, "raw", "")));

        verify(appService).applyVerificationOutcome(REQ_ID, VerificationOutcome.BLOCKED, "system:verify");
    }

    @Test
    public void missing_workspace_should_fail_before_transition() {
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.startVerifyRun(REQ_ID, ACTOR));
        verify(appService, never()).startVerify(anyString(), anyString());
    }

    @Test
    public void round_breaker_should_halt_before_transition_when_same_verdict_repeats() {
        com.example.agentweb.domain.verification.VerificationRoundRepository roundRepository =
                mock(com.example.agentweb.domain.verification.VerificationRoundRepository.class);
        when(roundRepository.findByRequirementId(REQ_ID)).thenReturn(List.of(
                com.example.agentweb.domain.verification.VerificationRound.record(
                        REQ_ID, 1, VerificationOutcome.BLOCKED, 0, ""),
                com.example.agentweb.domain.verification.VerificationRound.record(
                        REQ_ID, 2, VerificationOutcome.BLOCKED, 0, "")));
        VerifyRunService withRounds = roundAwareService(roundRepository);

        assertThrows(com.example.agentweb.domain.verification.VerificationHaltedException.class,
                () -> withRounds.startVerifyRun(REQ_ID, ACTOR));
        verify(appService, never()).startVerify(anyString(), anyString());
        verify(launcher, never()).launch(anyString(), any(), any());
    }

    @Test
    public void completed_verify_should_record_round_with_incremented_number() {
        com.example.agentweb.domain.verification.VerificationRoundRepository roundRepository =
                mock(com.example.agentweb.domain.verification.VerificationRoundRepository.class);
        when(roundRepository.findByRequirementId(REQ_ID))
                .thenReturn(List.of())
                .thenReturn(List.of(com.example.agentweb.domain.verification.VerificationRound.record(
                        REQ_ID, 1, VerificationOutcome.VERIFIED, 0, "")));
        VerifyRunService withRounds = roundAwareService(roundRepository);
        when(collector.collect(workspace.getWorktreePath())).thenReturn(new CollectedVerification(
                VerificationOutcome.BLOCKED, List.of(), null));
        when(appService.applyVerificationOutcome(REQ_ID, VerificationOutcome.BLOCKED, "system:verify"))
                .thenReturn(RequirementStatus.SUSPENDED);

        withRounds.startVerifyRun(REQ_ID, ACTOR);
        completeRun(new RunResult(0, "raw", ""));

        ArgumentCaptor<com.example.agentweb.domain.verification.VerificationRound> roundCaptor =
                ArgumentCaptor.forClass(com.example.agentweb.domain.verification.VerificationRound.class);
        verify(roundRepository).save(roundCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(2, roundCaptor.getValue().getRound());
        org.junit.jupiter.api.Assertions.assertEquals(VerificationOutcome.BLOCKED,
                roundCaptor.getValue().getVerdict());
    }

    private VerifyRunService roundAwareService(
            com.example.agentweb.domain.verification.VerificationRoundRepository roundRepository) {
        PromptAssemblyService promptAssembly = mock(PromptAssemblyService.class);
        PromptAssemblyResult result = mock(PromptAssemblyResult.class);
        when(result.getPrompt()).thenReturn("ASSEMBLED");
        when(promptAssembly.assemble(any())).thenReturn(result);
        return new VerifyRunService(appService, workspaceRepository, mock(PortLeaseStore.class),
                promptAssembly, launcher, collector, artifactStore,
                new RequirementProperties(), roundRepository);
    }

    private void completeRun(RunResult result) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<RunResult>> callbackCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(launcher).launch(eq(REQ_ID), any(RunProfile.class), callbackCaptor.capture());
        callbackCaptor.getValue().accept(result);
    }
}
