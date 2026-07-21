package com.example.agentweb.app.requirement;

import com.example.agentweb.adapter.requirement.FetchedDoc;
import com.example.agentweb.adapter.requirement.RequirementDocFetcher;
import com.example.agentweb.app.agentrun.AgentRunContext;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.agentrun.RunForm;
import com.example.agentweb.app.agentrun.RunRecallPolicyFactory;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementNotFoundException;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.shared.AgentType;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 计划门编排（detailed-design §3.1）：前置断言 → 文档拉取（可空跳过、失败降级）→ 模板渲染
 * → PLAN run → 产出计划文本 attachPlan [T1/T2]。审批仍走 T4 人审门，本服务不碰 approve。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class PlanRunService {

    private static final String TEMPLATE = "/requirement-plan-prompt.md";

    private final RequirementRepository repository;
    private final RequirementAppService appService;
    private final List<RequirementDocFetcher> docFetchers;
    private final PromptAssemblyService promptAssemblyService;
    private final RequirementRunLauncher launcher;
    private final RequirementProperties properties;
    private final RunRecallPolicyFactory recallPolicyFactory;

    /** 6 参构造器：不接知识召回（M4 前存量调用方，policy 走 AgentRunContext 默认 disabled）。 */
    public PlanRunService(RequirementRepository repository, RequirementAppService appService,
                          List<RequirementDocFetcher> docFetchers, PromptAssemblyService promptAssemblyService,
                          RequirementRunLauncher launcher, RequirementProperties properties) {
        this(repository, appService, docFetchers, promptAssemblyService, launcher, properties, null);
    }

    public PlanRunService(RequirementRepository repository, RequirementAppService appService,
                          List<RequirementDocFetcher> docFetchers, PromptAssemblyService promptAssemblyService,
                          RequirementRunLauncher launcher, RequirementProperties properties,
                          RunRecallPolicyFactory recallPolicyFactory) {
        this.repository = repository;
        this.appService = appService;
        this.docFetchers = docFetchers;
        this.promptAssemblyService = promptAssemblyService;
        this.launcher = launcher;
        this.properties = properties;
        this.recallPolicyFactory = recallPolicyFactory;
    }

    /**
     * 发起计划 run（异步，完成后自动贴计划）。
     *
     * @param requirementId 需求 ID
     * @param actor         发起人（计划完成后 attachPlan 的 actor）
     */
    public void startPlanRun(String requirementId, String actor) {
        Requirement requirement = load(requirementId);
        requirement.assertPlanAttachable();
        launcher.assertRunQuota(requirementId);

        String docMarkdown = fetchDocSafely(requirement);
        String prompt = PromptTemplates.render(TEMPLATE, Map.of(
                "title", requirement.getTitle(),
                "description", requirement.getDescription() == null ? "" : requirement.getDescription(),
                "docMarkdown", docMarkdown));
        String workingDir = ensuredPlanWorkingDir();
        AgentRunContext.Builder contextBuilder = AgentRunContext.builder()
                .originalInput(prompt)
                .runForm(RunForm.PLAN)
                .requirementId(requirementId)
                .agentType(runAgentType())
                .workingDir(workingDir);
        if (recallPolicyFactory != null) {
            contextBuilder.recallPolicy(recallPolicyFactory.forRun(RunForm.PLAN, SourceType.GENERAL));
        }
        AgentRunContext context = contextBuilder.build();
        String assembled = promptAssemblyService.assemble(context).getPrompt();

        RunProfile profile = new RunProfile("plan", runAgentType(), workingDir, assembled,
                properties.getPlan().getRunTimeoutMinutes() * 60L, null);
        launcher.launch(requirementId, profile, result -> completePlanRun(requirementId, actor, result));
    }

    private void completePlanRun(String requirementId, String actor, RunResult result) {
        if (result.getExitCode() != 0 || result.getPlainText() == null || result.getPlainText().isBlank()) {
            log.error("req-plan-run-unusable requirementId={} exitCode={} textLen={}",
                    requirementId, result.getExitCode(),
                    result.getPlainText() == null ? 0 : result.getPlainText().length());
            return;
        }
        appService.attachPlan(requirementId, result.getPlainText(), actor);
        log.info("req-plan-attached requirementId={} actor={} planLen={}",
                requirementId, actor, result.getPlainText().length());
    }

    /** 文档拉取失败只降级为空正文（计划仍可基于标题+描述生成），绝不阻断计划门。 */
    private String fetchDocSafely(Requirement requirement) {
        String sourceRef = requirement.getSourceRef();
        if (sourceRef == null || sourceRef.isBlank()) {
            return "";
        }
        try {
            return docFetchers.stream()
                    .filter(fetcher -> fetcher.supports(sourceRef))
                    .findFirst()
                    .map(fetcher -> fetcher.fetch(sourceRef))
                    .map(FetchedDoc::getMarkdown)
                    .orElse("");
        } catch (RuntimeException e) {
            log.warn("req-plan-doc-fetch-degraded requirementId={} sourceRef={} reason={}",
                    requirement.getId().getValue(), sourceRef, e.getMessage());
            return "";
        }
    }

    private String ensuredPlanWorkingDir() {
        Path dir = Path.of(properties.getPlan().getWorkingDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("计划 run 工作目录创建失败: " + dir, e);
        }
        return dir.toAbsolutePath().toString();
    }

    private AgentType runAgentType() {
        return AgentType.valueOf(properties.getRunAgentType().trim().toUpperCase(java.util.Locale.ROOT));
    }

    private Requirement load(String requirementId) {
        Requirement requirement = repository.findById(requirementId);
        if (requirement == null) {
            throw new RequirementNotFoundException(requirementId);
        }
        return requirement;
    }
}
