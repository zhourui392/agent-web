package com.example.agentweb.app.requirement;

import com.example.agentweb.adapter.verification.CollectedVerification;
import com.example.agentweb.adapter.verification.VerificationArtifactCollector;
import com.example.agentweb.app.agentrun.AgentRunContext;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.agentrun.RunForm;
import com.example.agentweb.app.verification.ArtifactStore;
import com.example.agentweb.app.workspace.PortLeaseStore;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.verification.RoundBreakerPolicy;
import com.example.agentweb.domain.verification.VerificationOutcome;
import com.example.agentweb.domain.verification.VerificationRound;
import com.example.agentweb.domain.verification.VerificationRoundRepository;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * L1 验证编排（detailed-design §4.2）：startVerify [T7] → goal-workflow 长 run →
 * 工件采集入库 → 终态映射（缺证据按退出码兜底）→ applyVerificationOutcome [T8/T9]。
 * 采集是旁路：失败只降级，绝不卡死状态机。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class VerifyRunService {

    private static final String TEMPLATE = "/requirement-verify-prompt.md";

    private static final String ACTOR_SYSTEM_VERIFY = "system:verify";

    private final RequirementAppService appService;
    private final WorkspaceRepository workspaceRepository;
    private final PortLeaseStore portLeaseStore;
    private final PromptAssemblyService promptAssemblyService;
    private final RequirementRunLauncher launcher;
    private final VerificationArtifactCollector artifactCollector;
    private final ArtifactStore artifactStore;
    private final RequirementProperties properties;
    private final VerificationRoundRepository roundRepository;

    /** 熔断策略（M4.5，纠偏后）：阈值从配置注入，是资源硬上限而非方法论口径。 */
    private final RoundBreakerPolicy roundBreaker;

    /** 8 参构造器：无轮次仓储（M4.5 前存量调用方，熔断闸与轮次记录不生效）。 */
    public VerifyRunService(RequirementAppService appService, WorkspaceRepository workspaceRepository,
                            PortLeaseStore portLeaseStore, PromptAssemblyService promptAssemblyService,
                            RequirementRunLauncher launcher, VerificationArtifactCollector artifactCollector,
                            ArtifactStore artifactStore, RequirementProperties properties) {
        this(appService, workspaceRepository, portLeaseStore, promptAssemblyService, launcher,
                artifactCollector, artifactStore, properties, null);
    }

    public VerifyRunService(RequirementAppService appService, WorkspaceRepository workspaceRepository,
                            PortLeaseStore portLeaseStore, PromptAssemblyService promptAssemblyService,
                            RequirementRunLauncher launcher, VerificationArtifactCollector artifactCollector,
                            ArtifactStore artifactStore,
                            RequirementProperties properties, VerificationRoundRepository roundRepository) {
        this.appService = appService;
        this.workspaceRepository = workspaceRepository;
        this.portLeaseStore = portLeaseStore;
        this.promptAssemblyService = promptAssemblyService;
        this.launcher = launcher;
        this.artifactCollector = artifactCollector;
        this.artifactStore = artifactStore;
        this.properties = properties;
        this.roundRepository = roundRepository;
        this.roundBreaker = new RoundBreakerPolicy(
                properties.getVerify().getMaxConsecutiveFailedRounds(),
                properties.getVerify().getMaxSameVerdictFailures());
    }

    /**
     * 发起验证 run（异步）。
     *
     * @param requirementId 需求 ID
     * @param actor         发起人（T7 的 actor；终态应用一律记 system:verify）
     */
    public void startVerifyRun(String requirementId, String actor) {
        launcher.assertRunQuota(requirementId);
        if (roundRepository != null) {
            roundBreaker.assertCanStartNextRound(roundRepository.findByRequirementId(requirementId));
        }
        RequirementWorkspace workspace = requiredWorkspace(requirementId);

        String prompt = PromptTemplates.render(TEMPLATE, Map.of("requirementId", requirementId));
        AgentRunContext context = AgentRunContext.builder()
                .originalInput(prompt)
                .runForm(RunForm.CUSTOM)
                .requirementId(requirementId)
                .agentType(runAgentType())
                .workingDir(workspace.getWorktreePath())
                .build();
        String assembled = promptAssemblyService.assemble(context).getPrompt();

        RunProfile profile = new RunProfile("verify", runAgentType(), workspace.getWorktreePath(),
                assembled, properties.getVerify().getRunTimeoutHours() * 3600L, devPortEnv(workspace));
        appService.startVerify(requirementId, actor);
        launcher.launch(requirementId, profile,
                result -> completeVerifyRun(requirementId, workspace.getWorktreePath(), result));
    }

    private void completeVerifyRun(String requirementId, String worktreePath, RunResult result) {
        CollectedVerification collected = artifactCollector.collect(worktreePath);
        saveArtifactsSafely(requirementId, collected);

        VerificationOutcome outcome = collected.getOutcome() != null
                ? collected.getOutcome()
                : VerificationOutcome.fallbackForExit(result.getExitCode());
        if (collected.getOutcome() == null) {
            log.warn("req-verify-degraded requirementId={} exitCode={} reason={}",
                    requirementId, result.getExitCode(), collected.getDegradeReason());
        }
        appService.applyVerificationOutcome(requirementId, outcome, ACTOR_SYSTEM_VERIFY);
        recordRoundSafely(requirementId, outcome);
    }

    /** 轮次记录（M4.5）是旁路：落库失败只降级；failedCount L1 无逐 case 明细，恒 0。 */
    private void recordRoundSafely(String requirementId, VerificationOutcome outcome) {
        if (roundRepository == null) {
            return;
        }
        try {
            int nextRound = roundRepository.findByRequirementId(requirementId).size() + 1;
            roundRepository.save(VerificationRound.record(requirementId, nextRound, outcome, 0,
                    "requirement_artifact:" + requirementId));
        } catch (RuntimeException e) {
            log.warn("req-verify-round-record-failed requirementId={} reason={}",
                    requirementId, e.getMessage(), e);
        }
    }

    private void saveArtifactsSafely(String requirementId, CollectedVerification collected) {
        try {
            artifactStore.saveAll(requirementId, collected.getArtifacts(), Instant.now());
        } catch (RuntimeException e) {
            log.warn("req-verify-artifact-save-failed requirementId={} reason={}",
                    requirementId, e.getMessage(), e);
        }
    }

    private Map<String, String> devPortEnv(RequirementWorkspace workspace) {
        List<Integer> ports = portLeaseStore.portsOf(workspace.getId().getValue());
        if (ports == null || ports.isEmpty()) {
            return null;
        }
        return Map.of(ImplementRunService.ENV_DEV_PORT, String.valueOf(ports.get(0)));
    }

    private RequirementWorkspace requiredWorkspace(String requirementId) {
        RequirementWorkspace workspace = workspaceRepository.findByRequirementId(requirementId);
        if (workspace == null) {
            throw new IllegalStateException("verify rejected: workspace missing for " + requirementId);
        }
        return workspace;
    }

    private AgentType runAgentType() {
        return AgentType.valueOf(properties.getRunAgentType().trim().toUpperCase(Locale.ROOT));
    }
}
