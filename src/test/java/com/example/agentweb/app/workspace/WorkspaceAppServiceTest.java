package com.example.agentweb.app.workspace;

import com.example.agentweb.adapter.workspace.ProvisionRequest;
import com.example.agentweb.adapter.workspace.ProvisionedWorkspace;
import com.example.agentweb.adapter.workspace.WorkspaceProvisioner;
import com.example.agentweb.app.requirement.RequirementProperties;
import com.example.agentweb.domain.requirement.AgentPlan;
import com.example.agentweb.domain.requirement.IllegalRequirementTransitionException;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementNotFoundException;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceId;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import com.example.agentweb.domain.workspace.WorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工作区编排单测（Mock provisioner/repos + 真实聚合）：provision→save→attachWorkspace
 * 编排顺序；APPROVED 前拒绝靠真实聚合 T5 守卫，不 mock 出来（verification-plan M1 行 3）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@ExtendWith(MockitoExtension.class)
public class WorkspaceAppServiceTest {

    private static final String OWNER = "V33215020";

    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private PortLeaseStore portLeaseStore;
    @Mock
    private WorkspaceProvisioner provisioner;

    private WorkspaceAppService service;

    @BeforeEach
    public void setUp() {
        RequirementProperties properties = new RequirementProperties();
        properties.getWorkspace().setRepoUrl("http://git/repo.git");
        properties.getWorkspace().setTtlHours(72);
        service = new WorkspaceAppService(requirementRepository, workspaceRepository,
                portLeaseStore, provisioner, properties);
    }

    private Requirement approvedRequirement() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "标题", "描述", OWNER);
        requirement.attachPlan(new AgentPlan("计划", null, null, Instant.now()), OWNER);
        requirement.approve(OWNER);
        requirement.pullEvents();
        return requirement;
    }

    @Test
    public void provision_should_orchestrate_in_order_and_attach_workspace() {
        Requirement requirement = approvedRequirement();
        String requirementId = requirement.getId().getValue();
        when(requirementRepository.findById(requirementId)).thenReturn(requirement);
        when(workspaceRepository.findByRequirementId(requirementId)).thenReturn(null);
        when(provisioner.provision(any())).thenReturn(new ProvisionedWorkspace(
                "D:/ws/mirrors/repo.git", "D:/ws/worktrees/" + requirementId, "abc123"));
        when(portLeaseStore.allocate(anyString())).thenReturn(42000);

        String workspaceId = service.provisionFor(requirementId);

        assertEquals(workspaceId, requirement.getWorkspaceId());
        InOrder order = inOrder(provisioner, workspaceRepository, portLeaseStore, requirementRepository);
        order.verify(provisioner).provision(any(ProvisionRequest.class));
        order.verify(workspaceRepository).save(any(RequirementWorkspace.class));
        order.verify(portLeaseStore).allocate(workspaceId);
        order.verify(requirementRepository).update(requirement);

        ArgumentCaptor<ProvisionRequest> requestCaptor = ArgumentCaptor.forClass(ProvisionRequest.class);
        verify(provisioner).provision(requestCaptor.capture());
        assertEquals("req/" + requirementId, requestCaptor.getValue().getBranch());
        ArgumentCaptor<RequirementWorkspace> savedCaptor =
                ArgumentCaptor.forClass(RequirementWorkspace.class);
        verify(workspaceRepository).save(savedCaptor.capture());
        assertEquals(WorkspaceStatus.READY, savedCaptor.getValue().getStatus());
    }

    @Test
    public void provision_before_approval_should_be_rejected_by_t5_guard() {
        Requirement intake = Requirement.create(RequirementSource.BOARD, "标题", null, OWNER);
        when(requirementRepository.findById(intake.getId().getValue())).thenReturn(intake);
        when(workspaceRepository.findByRequirementId(intake.getId().getValue())).thenReturn(null);

        assertThrows(IllegalRequirementTransitionException.class,
                () -> service.provisionFor(intake.getId().getValue()));
        verifyNoInteractions(provisioner);
    }

    @Test
    public void provision_missing_requirement_should_throw_not_found() {
        when(requirementRepository.findById("R-none")).thenReturn(null);

        assertThrows(RequirementNotFoundException.class, () -> service.provisionFor("R-none"));
        verifyNoInteractions(provisioner);
    }

    @Test
    public void provision_should_reuse_active_workspace() {
        Requirement requirement = approvedRequirement();
        String requirementId = requirement.getId().getValue();
        RequirementWorkspace active = new RequirementWorkspace(WorkspaceId.newId(requirementId),
                requirementId, "http://git/repo.git", "m", "w", "req/" + requirementId,
                WorkspaceStatus.READY, 72, Instant.now());
        when(requirementRepository.findById(requirementId)).thenReturn(requirement);
        when(workspaceRepository.findByRequirementId(requirementId)).thenReturn(active);

        String workspaceId = service.provisionFor(requirementId);

        assertEquals(active.getId().getValue(), workspaceId);
        verifyNoInteractions(provisioner);
    }

    @Test
    public void provision_should_reprovision_after_release() {
        Requirement requirement = approvedRequirement();
        String requirementId = requirement.getId().getValue();
        RequirementWorkspace released = new RequirementWorkspace(WorkspaceId.newId(requirementId),
                requirementId, "http://git/repo.git", "m", "w", "req/" + requirementId,
                WorkspaceStatus.RELEASED, 72, Instant.now());
        when(requirementRepository.findById(requirementId)).thenReturn(requirement);
        when(workspaceRepository.findByRequirementId(requirementId)).thenReturn(released);
        when(provisioner.provision(any())).thenReturn(new ProvisionedWorkspace("m", "w", "abc"));
        when(portLeaseStore.allocate(anyString())).thenReturn(42000);

        service.provisionFor(requirementId);

        verify(provisioner).provision(any(ProvisionRequest.class));
    }
}
