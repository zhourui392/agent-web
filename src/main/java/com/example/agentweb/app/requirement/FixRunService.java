package com.example.agentweb.app.requirement;

import com.example.agentweb.app.agentrun.AgentRunContext;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.agentrun.RunForm;
import com.example.agentweb.app.agentrun.RunRecallPolicyFactory;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.app.workspace.PortLeaseStore;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementEvent;
import com.example.agentweb.domain.requirement.RequirementNotFoundException;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 修复 run 编排（M2 遗留补齐）：REVIEW 退回 [T11] / 熔断 resume [T13] 后需求回到 IMPLEMENTING，
 * 重发 run 走 FIX 形态——守卫在聚合 startFixRun（仅审计不迁移），不再撞首实现的 T6。
 * 修复上下文取时间线里最近的退回/建议事件拼进 prompt。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class FixRunService {

    private static final String TEMPLATE = "/requirement-fix-prompt.md";

    /** 修复上下文最多携带的退回/建议条数（新在前，避免陈年建议淹没本轮退回原因）。 */
    private static final int FEEDBACK_MAX_ITEMS = 3;

    private final RequirementRepository repository;
    private final RequirementAppService appService;
    private final RequirementQueryService queryService;
    private final WorkspaceRepository workspaceRepository;
    private final PortLeaseStore portLeaseStore;
    private final PromptAssemblyService promptAssemblyService;
    private final RequirementRunLauncher launcher;
    private final RequirementProperties properties;
    private final RunRecallPolicyFactory recallPolicyFactory;

    /** 8 参构造器：不接知识召回（M4 前存量调用方，policy 走 AgentRunContext 默认 disabled）。 */
    public FixRunService(RequirementRepository repository, RequirementAppService appService,
                         RequirementQueryService queryService, WorkspaceRepository workspaceRepository,
                         PortLeaseStore portLeaseStore, PromptAssemblyService promptAssemblyService,
                         RequirementRunLauncher launcher, RequirementProperties properties) {
        this(repository, appService, queryService, workspaceRepository, portLeaseStore,
                promptAssemblyService, launcher, properties, null);
    }

    public FixRunService(RequirementRepository repository, RequirementAppService appService,
                         RequirementQueryService queryService, WorkspaceRepository workspaceRepository,
                         PortLeaseStore portLeaseStore, PromptAssemblyService promptAssemblyService,
                         RequirementRunLauncher launcher, RequirementProperties properties,
                         RunRecallPolicyFactory recallPolicyFactory) {
        this.repository = repository;
        this.appService = appService;
        this.queryService = queryService;
        this.workspaceRepository = workspaceRepository;
        this.portLeaseStore = portLeaseStore;
        this.promptAssemblyService = promptAssemblyService;
        this.launcher = launcher;
        this.properties = properties;
        this.recallPolicyFactory = recallPolicyFactory;
    }

    /**
     * 发起修复 run（异步）。编排序：配额闸 → 工作区在位检查 → FIX 守卫 + 审计事件 → 发 run；
     * 工作区检查放守卫前，避免落了 FIX_RUN_STARTED 事件却没有可执行的工作目录。
     *
     * @param requirementId 需求 ID
     * @param actor         发起人
     */
    public void startFixRun(String requirementId, String actor) {
        launcher.assertRunQuota(requirementId);
        RequirementWorkspace workspace = requiredWorkspace(requirementId);
        appService.startFixRun(requirementId, actor);

        Requirement requirement = load(requirementId);
        String prompt = PromptTemplates.render(TEMPLATE, Map.of(
                "requirementId", requirementId,
                "planText", requirement.getPlan() == null ? "" : requirement.getPlan().getPlanText(),
                "fixFeedback", latestFixFeedback(requirementId)));
        AgentRunContext.Builder contextBuilder = AgentRunContext.builder()
                .originalInput(prompt)
                .runForm(RunForm.FIX)
                .requirementId(requirementId)
                .agentType(runAgentType())
                .workingDir(workspace.getWorktreePath());
        if (recallPolicyFactory != null) {
            contextBuilder.recallPolicy(recallPolicyFactory.forRun(RunForm.FIX, SourceType.GENERAL));
        }
        AgentRunContext context = contextBuilder.build();
        String assembled = promptAssemblyService.assemble(context).getPrompt();

        RunProfile profile = new RunProfile("fix", runAgentType(), workspace.getWorktreePath(),
                assembled, properties.getImplement().getRunTimeoutMinutes() * 60L, runEnv(workspace));
        launcher.launch(requirementId, profile,
                result -> log.info("req-fix-run-done requirementId={} exitCode={}",
                        requirementId, result.getExitCode()));
    }

    /** 修复上下文（读侧投影）：时间线里最近的退回/建议事件，新在前，最多 3 条。 */
    private String latestFixFeedback(String requirementId) {
        List<RequirementEventView> events = queryService.listEvents(requirementId);
        List<String> feedback = new ArrayList<>();
        for (int i = events.size() - 1; i >= 0 && feedback.size() < FEEDBACK_MAX_ITEMS; i--) {
            RequirementEventView event = events.get(i);
            if (RequirementEvent.TYPE_CHANGES_REQUESTED.equals(event.eventType())
                    || RequirementEvent.TYPE_FIX_SUGGESTED.equals(event.eventType())) {
                feedback.add("- [" + event.eventType() + "] " + event.detail());
            }
        }
        return feedback.isEmpty()
                ? "（时间线无退回/建议记录，请结合验证工件与失败用例自查）"
                : String.join("\n", feedback);
    }

    /** 工具链 env（按 repoUrl 命中，M3-lite）+ 端口租约 AGENT_DEV_PORT 合并注入；两者皆无返回 null。 */
    private Map<String, String> runEnv(RequirementWorkspace workspace) {
        Map<String, String> env = new LinkedHashMap<>(
                ToolchainEnvs.resolve(properties.getWorkspace().getToolchains(), workspace.getRepoUrl()));
        List<Integer> ports = portLeaseStore.portsOf(workspace.getId().getValue());
        if (ports != null && !ports.isEmpty()) {
            env.put(ImplementRunService.ENV_DEV_PORT, String.valueOf(ports.get(0)));
        }
        return env.isEmpty() ? null : env;
    }

    private RequirementWorkspace requiredWorkspace(String requirementId) {
        RequirementWorkspace workspace = workspaceRepository.findByRequirementId(requirementId);
        if (workspace == null) {
            throw new IllegalStateException("fix rejected: workspace missing for " + requirementId);
        }
        return workspace;
    }

    private AgentType runAgentType() {
        return AgentType.valueOf(properties.getRunAgentType().trim().toUpperCase(Locale.ROOT));
    }

    private Requirement load(String requirementId) {
        Requirement requirement = repository.findById(requirementId);
        if (requirement == null) {
            throw new RequirementNotFoundException(requirementId);
        }
        return requirement;
    }
}
