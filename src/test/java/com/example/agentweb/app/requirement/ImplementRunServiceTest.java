package com.example.agentweb.app.requirement;

import com.example.agentweb.app.agentrun.PromptAssemblyResult;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.workspace.PortLeaseStore;
import com.example.agentweb.app.workspace.WorkspaceAppService;
import com.example.agentweb.domain.requirement.AgentPlan;
import com.example.agentweb.domain.requirement.Requirement;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 实现 run 编排：配额闸 → provision → T6 → 发 run（AGENT_DEV_PORT 注入）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class ImplementRunServiceTest {

    private static final String OWNER = "V33215020";
    private static final String REQ_ID = "R2607040001";

    private RequirementRepository repository;
    private RequirementAppService appService;
    private WorkspaceAppService workspaceAppService;
    private WorkspaceRepository workspaceRepository;
    private PortLeaseStore portLeaseStore;
    private PromptAssemblyService promptAssembly;
    private RequirementRunLauncher launcher;
    private ImplementRunService service;

    @BeforeEach
    public void setUp() {
        repository = mock(RequirementRepository.class);
        appService = mock(RequirementAppService.class);
        workspaceAppService = mock(WorkspaceAppService.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        portLeaseStore = mock(PortLeaseStore.class);
        promptAssembly = mock(PromptAssemblyService.class);
        launcher = mock(RequirementRunLauncher.class);
        service = new ImplementRunService(repository, appService, workspaceAppService,
                workspaceRepository, portLeaseStore, promptAssembly, launcher,
                new RequirementProperties());
        PromptAssemblyResult result = mock(PromptAssemblyResult.class);
        when(result.getPrompt()).thenReturn("ASSEMBLED");
        when(promptAssembly.assemble(any())).thenReturn(result);
    }

    @Test
    public void startImplementRun_should_orchestrate_quota_provision_transition_run() {
        RequirementWorkspace workspace = readyWorkspace();
        when(repository.findById(REQ_ID)).thenReturn(plannedRequirement());
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(workspace);
        when(portLeaseStore.portsOf(workspace.getId().getValue())).thenReturn(List.of(42007));

        service.startImplementRun(REQ_ID, OWNER);

        InOrder inOrder = inOrder(launcher, workspaceAppService, appService);
        inOrder.verify(launcher).assertRunQuota(REQ_ID);
        inOrder.verify(workspaceAppService).provisionFor(REQ_ID);
        inOrder.verify(appService).startImplement(REQ_ID, OWNER);
        ArgumentCaptor<RunProfile> profileCaptor = ArgumentCaptor.forClass(RunProfile.class);
        inOrder.verify(launcher).launch(eq(REQ_ID), profileCaptor.capture(), any());

        RunProfile profile = profileCaptor.getValue();
        assertEquals(workspace.getWorktreePath(), profile.getWorkingDir());
        assertEquals("42007", profile.getExtraEnv().get(ImplementRunService.ENV_DEV_PORT));
        assertTrue(profile.getAssembledPrompt().contains("ASSEMBLED"));
    }

    @Test
    public void startImplementRun_should_skip_port_env_when_no_lease() {
        RequirementWorkspace workspace = readyWorkspace();
        when(repository.findById(REQ_ID)).thenReturn(plannedRequirement());
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(workspace);
        when(portLeaseStore.portsOf(workspace.getId().getValue())).thenReturn(List.of());

        service.startImplementRun(REQ_ID, OWNER);

        ArgumentCaptor<RunProfile> profileCaptor = ArgumentCaptor.forClass(RunProfile.class);
        org.mockito.Mockito.verify(launcher).launch(eq(REQ_ID), profileCaptor.capture(), any());
        assertNull(profileCaptor.getValue().getExtraEnv());
    }

    @Test
    public void startImplementRun_should_merge_toolchain_env_with_dev_port() {
        RequirementProperties toolchainProps = new RequirementProperties();
        RequirementProperties.Toolchain toolchain = new RequirementProperties.Toolchain();
        toolchain.setRepoPattern("git/repo");
        toolchain.getEnv().put("JAVA_HOME", "C:/Java/jdk1.8");
        toolchainProps.getWorkspace().getToolchains().add(toolchain);
        ImplementRunService toolchainService = new ImplementRunService(repository, appService,
                workspaceAppService, workspaceRepository, portLeaseStore, promptAssembly, launcher,
                toolchainProps);
        RequirementWorkspace workspace = readyWorkspace();
        when(repository.findById(REQ_ID)).thenReturn(plannedRequirement());
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(workspace);
        when(portLeaseStore.portsOf(workspace.getId().getValue())).thenReturn(List.of(42007));

        toolchainService.startImplementRun(REQ_ID, OWNER);

        ArgumentCaptor<RunProfile> profileCaptor = ArgumentCaptor.forClass(RunProfile.class);
        org.mockito.Mockito.verify(launcher).launch(eq(REQ_ID), profileCaptor.capture(), any());
        assertEquals("C:/Java/jdk1.8", profileCaptor.getValue().getExtraEnv().get("JAVA_HOME"));
        assertEquals("42007", profileCaptor.getValue().getExtraEnv().get(ImplementRunService.ENV_DEV_PORT));
    }

    @Test
    public void startImplementRun_should_enable_workspace_knowledge_recall_via_factory() {
        com.example.agentweb.app.agentrun.RunRecallPolicyFactory factory =
                new com.example.agentweb.app.agentrun.RunRecallPolicyFactory(
                        new com.example.agentweb.infra.AgentRunProperties());
        ImplementRunService recallService = new ImplementRunService(repository, appService,
                workspaceAppService, workspaceRepository, portLeaseStore, promptAssembly, launcher,
                new RequirementProperties(), factory);
        RequirementWorkspace workspace = readyWorkspace();
        when(repository.findById(REQ_ID)).thenReturn(plannedRequirement());
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(workspace);
        when(portLeaseStore.portsOf(workspace.getId().getValue())).thenReturn(List.of());

        recallService.startImplementRun(REQ_ID, OWNER);

        ArgumentCaptor<com.example.agentweb.app.agentrun.AgentRunContext> contextCaptor =
                ArgumentCaptor.forClass(com.example.agentweb.app.agentrun.AgentRunContext.class);
        org.mockito.Mockito.verify(promptAssembly).assemble(contextCaptor.capture());
        assertTrue(contextCaptor.getValue().getRecallPolicy().isWorkspaceKnowledgeEnabled());
        assertTrue(!contextCaptor.getValue().getRecallPolicy().isHistoricalRagEnabled());
    }

    private Requirement plannedRequirement() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "标题", "d", OWNER);
        requirement.attachPlan(new AgentPlan("已审批计划", null, null, Instant.now()), OWNER);
        return requirement;
    }

    private RequirementWorkspace readyWorkspace() {
        RequirementWorkspace workspace = RequirementWorkspace.create(REQ_ID,
                "http://git/repo.git", "D:/ws/mirrors/repo.git", "D:/ws/worktrees/" + REQ_ID, 72);
        workspace.markReady();
        return workspace;
    }
}
