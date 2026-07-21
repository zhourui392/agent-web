package com.example.agentweb.app.requirement;

import com.example.agentweb.adapter.requirement.FetchedDoc;
import com.example.agentweb.adapter.requirement.RequirementDocFetcher;
import com.example.agentweb.app.agentrun.AgentRunContext;
import com.example.agentweb.app.agentrun.PromptAssemblyResult;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.agentrun.RunForm;
import com.example.agentweb.domain.requirement.AgentPlan;
import com.example.agentweb.domain.requirement.IllegalRequirementTransitionException;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.requirement.RequirementSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划门编排：文档拉取降级、模板渲染、run 完成后贴计划、坏结果不贴。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class PlanRunServiceTest {

    private static final String OWNER = "V33215020";

    @TempDir
    Path tempDir;

    private RequirementRepository repository;
    private RequirementAppService appService;
    private RequirementDocFetcher docFetcher;
    private PromptAssemblyService promptAssembly;
    private RequirementRunLauncher launcher;
    private RequirementProperties properties;
    private PlanRunService service;

    @BeforeEach
    public void setUp() {
        repository = mock(RequirementRepository.class);
        appService = mock(RequirementAppService.class);
        docFetcher = mock(RequirementDocFetcher.class);
        promptAssembly = mock(PromptAssemblyService.class);
        launcher = mock(RequirementRunLauncher.class);
        properties = new RequirementProperties();
        properties.getPlan().setWorkingDir(tempDir.resolve("plan-runs").toString());
        service = new PlanRunService(repository, appService, List.of(docFetcher),
                promptAssembly, launcher, properties);
        stubAssembly();
    }

    @Test
    public void startPlanRun_should_fetch_doc_and_attach_plan_on_success() {
        Requirement requirement = Requirement.create(RequirementSource.GITLAB_ISSUE,
                "https://shimo.im/docs/abc", "标题", "描述", OWNER);
        when(repository.findById("R1")).thenReturn(requirement);
        when(docFetcher.supports("https://shimo.im/docs/abc")).thenReturn(true);
        when(docFetcher.fetch("https://shimo.im/docs/abc"))
                .thenReturn(new FetchedDoc("文档", "# 正文", Instant.now()));

        service.startPlanRun("R1", OWNER);

        ArgumentCaptor<AgentRunContext> contextCaptor = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(promptAssembly).assemble(contextCaptor.capture());
        assertEquals(RunForm.PLAN, contextCaptor.getValue().getRunForm());
        assertEquals("R1", contextCaptor.getValue().getRequirementId());
        assertTrue(contextCaptor.getValue().getOriginalInput().contains("标题"));
        assertTrue(contextCaptor.getValue().getOriginalInput().contains("# 正文"));

        completeRun(new RunResult(0, "raw", "产出的计划"));
        verify(appService).attachPlan("R1", "产出的计划", OWNER);
    }

    @Test
    public void startPlanRun_should_enable_workspace_knowledge_recall_via_factory() {
        PlanRunService recallService = new PlanRunService(repository, appService, List.of(docFetcher),
                promptAssembly, launcher, properties,
                new com.example.agentweb.app.agentrun.RunRecallPolicyFactory(
                        new com.example.agentweb.infra.AgentRunProperties()));
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "标题", "描述", OWNER);
        when(repository.findById("R1")).thenReturn(requirement);

        recallService.startPlanRun("R1", OWNER);

        ArgumentCaptor<AgentRunContext> contextCaptor = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(promptAssembly).assemble(contextCaptor.capture());
        assertTrue(contextCaptor.getValue().getRecallPolicy().isWorkspaceKnowledgeEnabled());
    }

    @Test
    public void startPlanRun_should_degrade_when_doc_fetch_fails() {
        Requirement requirement = Requirement.create(RequirementSource.GITLAB_ISSUE,
                "https://shimo.im/docs/abc", "标题", null, OWNER);
        when(repository.findById("R1")).thenReturn(requirement);
        when(docFetcher.supports(anyString())).thenReturn(true);
        when(docFetcher.fetch(anyString())).thenThrow(new IllegalStateException("网页抓取失败"));

        service.startPlanRun("R1", OWNER);

        // 拉取失败只降级为空正文,run 照发
        verify(launcher).launch(eq("R1"), any(RunProfile.class), any());
    }

    @Test
    public void startPlanRun_should_skip_fetch_without_source_ref() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "标题", "d", OWNER);
        when(repository.findById("R1")).thenReturn(requirement);

        service.startPlanRun("R1", OWNER);

        verify(docFetcher, never()).fetch(anyString());
        verify(launcher).launch(eq("R1"), any(RunProfile.class), any());
    }

    @Test
    public void startPlanRun_should_fail_fast_when_not_plan_attachable() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "标题", "d", OWNER);
        requirement.attachPlan(new AgentPlan("p", null, null, Instant.now()), OWNER);
        requirement.approve(OWNER);
        when(repository.findById("R1")).thenReturn(requirement);

        assertThrows(IllegalRequirementTransitionException.class,
                () -> service.startPlanRun("R1", OWNER));
        verify(launcher, never()).launch(anyString(), any(), any());
    }

    @Test
    public void completePlanRun_should_not_attach_on_bad_result() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "标题", "d", OWNER);
        when(repository.findById("R1")).thenReturn(requirement);
        service.startPlanRun("R1", OWNER);

        completeRun(new RunResult(1, "raw", "有文本但退出非0"));
        completeRun(new RunResult(0, "raw", "  "));

        verify(appService, never()).attachPlan(anyString(), anyString(), anyString());
    }

    private void completeRun(RunResult result) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<RunResult>> callbackCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(launcher).launch(eq("R1"), any(RunProfile.class), callbackCaptor.capture());
        callbackCaptor.getValue().accept(result);
    }

    private void stubAssembly() {
        PromptAssemblyResult result = mock(PromptAssemblyResult.class);
        when(result.getPrompt()).thenReturn("ASSEMBLED");
        when(promptAssembly.assemble(any())).thenReturn(result);
    }
}
