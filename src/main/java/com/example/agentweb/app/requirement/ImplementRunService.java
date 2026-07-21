package com.example.agentweb.app.requirement;

import com.example.agentweb.app.agentrun.AgentRunContext;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.agentrun.RunForm;
import com.example.agentweb.app.agentrun.RunRecallPolicyFactory;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.app.workspace.PortLeaseStore;
import com.example.agentweb.app.workspace.WorkspaceAppService;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementNotFoundException;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 实现 run 编排（detailed-design §3.1）：审批后 provision 工作区 [T5] → startImplement [T6]
 * → 在 worktree 发 IMPLEMENT run，端口租约经 AGENT_DEV_PORT 注入 run 进程 env（M1 遗留执行点）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class ImplementRunService {

    /** 端口租约注入 run 的环境变量名（master-plan 约定）。 */
    public static final String ENV_DEV_PORT = "AGENT_DEV_PORT";

    private static final String TEMPLATE = "/requirement-implement-prompt.md";

    private final RequirementRepository repository;
    private final RequirementAppService appService;
    private final WorkspaceAppService workspaceAppService;
    private final WorkspaceRepository workspaceRepository;
    private final PortLeaseStore portLeaseStore;
    private final PromptAssemblyService promptAssemblyService;
    private final RequirementRunLauncher launcher;
    private final RequirementProperties properties;
    private final RunRecallPolicyFactory recallPolicyFactory;

    /** 8 参构造器：不接知识召回（M4 前存量调用方，policy 走 AgentRunContext 默认 disabled）。 */
    public ImplementRunService(RequirementRepository repository, RequirementAppService appService,
                               WorkspaceAppService workspaceAppService, WorkspaceRepository workspaceRepository,
                               PortLeaseStore portLeaseStore, PromptAssemblyService promptAssemblyService,
                               RequirementRunLauncher launcher, RequirementProperties properties) {
        this(repository, appService, workspaceAppService, workspaceRepository, portLeaseStore,
                promptAssemblyService, launcher, properties, null);
    }

    public ImplementRunService(RequirementRepository repository, RequirementAppService appService,
                               WorkspaceAppService workspaceAppService, WorkspaceRepository workspaceRepository,
                               PortLeaseStore portLeaseStore, PromptAssemblyService promptAssemblyService,
                               RequirementRunLauncher launcher, RequirementProperties properties,
                               RunRecallPolicyFactory recallPolicyFactory) {
        this.repository = repository;
        this.appService = appService;
        this.workspaceAppService = workspaceAppService;
        this.workspaceRepository = workspaceRepository;
        this.portLeaseStore = portLeaseStore;
        this.promptAssemblyService = promptAssemblyService;
        this.launcher = launcher;
        this.properties = properties;
        this.recallPolicyFactory = recallPolicyFactory;
    }

    /**
     * 发起实现 run（异步）。编排序：provisionFor（幂等，内部完成 T5）→ startImplement [T6]
     * → 发 run。首个实现 run 要求 APPROVED；REVIEW 退回后的修复 run 走 {@link FixRunService}。
     *
     * @param requirementId 需求 ID
     * @param actor         发起人
     */
    public void startImplementRun(String requirementId, String actor) {
        launcher.assertRunQuota(requirementId);
        workspaceAppService.provisionFor(requirementId);
        appService.startImplement(requirementId, actor);

        Requirement requirement = load(requirementId);
        RequirementWorkspace workspace = requiredWorkspace(requirementId);
        String prompt = PromptTemplates.render(TEMPLATE, Map.of(
                "requirementId", requirementId,
                "planText", requirement.getPlan() == null ? "" : requirement.getPlan().getPlanText()));
        AgentRunContext.Builder contextBuilder = AgentRunContext.builder()
                .originalInput(prompt)
                .runForm(RunForm.IMPLEMENT)
                .requirementId(requirementId)
                .agentType(runAgentType())
                .workingDir(workspace.getWorktreePath());
        if (recallPolicyFactory != null) {
            contextBuilder.recallPolicy(recallPolicyFactory.forRun(RunForm.IMPLEMENT, SourceType.GENERAL));
        }
        AgentRunContext context = contextBuilder.build();
        String assembled = promptAssemblyService.assemble(context).getPrompt();

        RunProfile profile = new RunProfile("implement", runAgentType(), workspace.getWorktreePath(),
                assembled, properties.getImplement().getRunTimeoutMinutes() * 60L, runEnv(workspace));
        launcher.launch(requirementId, profile,
                result -> log.info("req-implement-run-done requirementId={} exitCode={}",
                        requirementId, result.getExitCode()));
    }

    /** 工具链 env（按 repoUrl 命中，M3-lite）+ 端口租约 AGENT_DEV_PORT 合并注入；两者皆无返回 null。 */
    private Map<String, String> runEnv(RequirementWorkspace workspace) {
        Map<String, String> env = new LinkedHashMap<>(
                ToolchainEnvs.resolve(properties.getWorkspace().getToolchains(), workspace.getRepoUrl()));
        List<Integer> ports = portLeaseStore.portsOf(workspace.getId().getValue());
        if (ports != null && !ports.isEmpty()) {
            env.put(ENV_DEV_PORT, String.valueOf(ports.get(0)));
        }
        return env.isEmpty() ? null : env;
    }

    private RequirementWorkspace requiredWorkspace(String requirementId) {
        RequirementWorkspace workspace = workspaceRepository.findByRequirementId(requirementId);
        if (workspace == null) {
            throw new IllegalStateException("workspace missing after provision: " + requirementId);
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
