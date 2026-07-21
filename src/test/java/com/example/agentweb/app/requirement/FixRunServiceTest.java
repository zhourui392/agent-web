package com.example.agentweb.app.requirement;

import com.example.agentweb.app.agentrun.AgentRunContext;
import com.example.agentweb.app.agentrun.PromptAssemblyResult;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.agentrun.RunForm;
import com.example.agentweb.app.workspace.PortLeaseStore;
import com.example.agentweb.domain.requirement.AgentPlan;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementEvent;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 修复 run 编排：配额闸 → 工作区在位 → FIX 守卫审计 → 发 run（退回/建议事件拼进修复上下文）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class FixRunServiceTest {

    private static final String OWNER = "V33215020";
    private static final String REQ_ID = "R2607040001";

    private RequirementRepository repository;
    private RequirementAppService appService;
    private RequirementQueryService queryService;
    private WorkspaceRepository workspaceRepository;
    private PortLeaseStore portLeaseStore;
    private PromptAssemblyService promptAssembly;
    private RequirementRunLauncher launcher;
    private FixRunService service;

    @BeforeEach
    public void setUp() {
        repository = mock(RequirementRepository.class);
        appService = mock(RequirementAppService.class);
        queryService = mock(RequirementQueryService.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        portLeaseStore = mock(PortLeaseStore.class);
        promptAssembly = mock(PromptAssemblyService.class);
        launcher = mock(RequirementRunLauncher.class);
        service = new FixRunService(repository, appService, queryService, workspaceRepository,
                portLeaseStore, promptAssembly, launcher, new RequirementProperties());
    }

    @Test
    public void startFixRun_should_orchestrate_quota_workspace_guard_then_launch() {
        RequirementWorkspace workspace = readyWorkspace();
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(workspace);
        when(repository.findById(REQ_ID)).thenReturn(implementingRequirement());
        when(queryService.listEvents(REQ_ID)).thenReturn(List.of());
        when(portLeaseStore.portsOf(workspace.getId().getValue())).thenReturn(List.of(42007));
        stubAssembly();

        service.startFixRun(REQ_ID, OWNER);

        InOrder inOrder = inOrder(launcher, workspaceRepository, appService);
        inOrder.verify(launcher).assertRunQuota(REQ_ID);
        inOrder.verify(workspaceRepository).findByRequirementId(REQ_ID);
        inOrder.verify(appService).startFixRun(REQ_ID, OWNER);
        ArgumentCaptor<RunProfile> profileCaptor = ArgumentCaptor.forClass(RunProfile.class);
        inOrder.verify(launcher).launch(eq(REQ_ID), profileCaptor.capture(), any());

        RunProfile profile = profileCaptor.getValue();
        assertEquals("fix", profile.getRunKind());
        assertEquals(workspace.getWorktreePath(), profile.getWorkingDir());
        assertEquals("42007", profile.getExtraEnv().get(ImplementRunService.ENV_DEV_PORT));
    }

    @Test
    public void startFixRun_should_inject_latest_feedback_into_prompt_newest_first() {
        RequirementWorkspace workspace = readyWorkspace();
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(workspace);
        when(repository.findById(REQ_ID)).thenReturn(implementingRequirement());
        when(queryService.listEvents(REQ_ID)).thenReturn(List.of(
                eventOf(1, RequirementEvent.TYPE_CREATED, null),
                eventOf(2, RequirementEvent.TYPE_FIX_SUGGESTED, "pipeline 失败[failed]: http://ci/1"),
                eventOf(3, RequirementEvent.TYPE_FIX_SUGGESTED, "MR !7 新评论(alice): 空指针"),
                eventOf(4, RequirementEvent.TYPE_FIX_SUGGESTED, "MR !7 新评论(bob): 建议加重试"),
                eventOf(5, RequirementEvent.TYPE_CHANGES_REQUESTED, "评审退回: 补幂等")));
        stubAssembly();

        service.startFixRun(REQ_ID, OWNER);

        ArgumentCaptor<AgentRunContext> contextCaptor = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(promptAssembly).assemble(contextCaptor.capture());
        AgentRunContext context = contextCaptor.getValue();
        assertEquals(RunForm.FIX, context.getRunForm());
        String prompt = context.getOriginalInput();
        assertTrue(prompt.contains("评审退回: 补幂等"));
        assertTrue(prompt.contains("建议加重试"));
        assertTrue(prompt.contains("空指针"));
        // 最多 3 条,最旧的 pipeline 建议被挤出
        assertTrue(!prompt.contains("pipeline 失败"));
        // 新在前:退回原因排在建议之前
        assertTrue(prompt.indexOf("补幂等") < prompt.indexOf("建议加重试"));
        assertTrue(prompt.contains("已审批计划"));
    }

    @Test
    public void startFixRun_without_workspace_should_fail_before_guard_event() {
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.startFixRun(REQ_ID, OWNER));
        verify(appService, never()).startFixRun(anyString(), anyString());
        verify(launcher, never()).launch(anyString(), any(), any());
    }

    @Test
    public void startFixRun_should_merge_toolchain_env_with_dev_port() {
        RequirementProperties toolchainProps = new RequirementProperties();
        RequirementProperties.Toolchain toolchain = new RequirementProperties.Toolchain();
        toolchain.setRepoPattern("git/repo");
        toolchain.getEnv().put("JAVA_HOME", "C:/Java/jdk1.8");
        toolchainProps.getWorkspace().getToolchains().add(toolchain);
        FixRunService toolchainService = new FixRunService(repository, appService, queryService,
                workspaceRepository, portLeaseStore, promptAssembly, launcher, toolchainProps);
        RequirementWorkspace workspace = readyWorkspace();
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(workspace);
        when(repository.findById(REQ_ID)).thenReturn(implementingRequirement());
        when(queryService.listEvents(REQ_ID)).thenReturn(List.of());
        when(portLeaseStore.portsOf(workspace.getId().getValue())).thenReturn(List.of(42008));
        stubAssembly();

        toolchainService.startFixRun(REQ_ID, OWNER);

        ArgumentCaptor<RunProfile> profileCaptor = ArgumentCaptor.forClass(RunProfile.class);
        verify(launcher).launch(eq(REQ_ID), profileCaptor.capture(), any());
        assertEquals("C:/Java/jdk1.8", profileCaptor.getValue().getExtraEnv().get("JAVA_HOME"));
        assertEquals("42008",
                profileCaptor.getValue().getExtraEnv().get(ImplementRunService.ENV_DEV_PORT));
    }

    @Test
    public void startFixRun_should_enable_workspace_knowledge_recall_via_factory() {
        FixRunService recallService = new FixRunService(repository, appService, queryService,
                workspaceRepository, portLeaseStore, promptAssembly, launcher,
                new RequirementProperties(),
                new com.example.agentweb.app.agentrun.RunRecallPolicyFactory(
                        new com.example.agentweb.infra.AgentRunProperties()));
        RequirementWorkspace workspace = readyWorkspace();
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(workspace);
        when(repository.findById(REQ_ID)).thenReturn(implementingRequirement());
        when(queryService.listEvents(REQ_ID)).thenReturn(List.of());
        when(portLeaseStore.portsOf(workspace.getId().getValue())).thenReturn(List.of());
        stubAssembly();

        recallService.startFixRun(REQ_ID, OWNER);

        ArgumentCaptor<AgentRunContext> contextCaptor = ArgumentCaptor.forClass(AgentRunContext.class);
        verify(promptAssembly).assemble(contextCaptor.capture());
        assertTrue(contextCaptor.getValue().getRecallPolicy().isWorkspaceKnowledgeEnabled());
    }

    private void stubAssembly() {
        PromptAssemblyResult result = mock(PromptAssemblyResult.class);
        when(result.getPrompt()).thenReturn("ASSEMBLED");
        when(promptAssembly.assemble(any())).thenReturn(result);
    }

    private RequirementEventView eventOf(long id, String type, String detail) {
        return new RequirementEventView(id, type, OWNER, null, null, detail, id * 1000L);
    }

    private Requirement implementingRequirement() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "标题", "d", OWNER);
        requirement.attachPlan(new AgentPlan("已审批计划", null, null, Instant.now()), OWNER);
        requirement.approve(OWNER);
        requirement.attachWorkspace("W1");
        requirement.startImplement(OWNER);
        return requirement;
    }

    private RequirementWorkspace readyWorkspace() {
        RequirementWorkspace workspace = RequirementWorkspace.create(REQ_ID,
                "http://git/repo.git", "D:/ws/mirrors/repo.git", "D:/ws/worktrees/" + REQ_ID, 72);
        workspace.markReady();
        return workspace;
    }
}
