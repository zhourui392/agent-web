package com.example.agentweb.app.workspace;

import com.example.agentweb.adapter.workspace.ProvisionRequest;
import com.example.agentweb.adapter.workspace.ProvisionedWorkspace;
import com.example.agentweb.adapter.workspace.WorkspaceProvisioner;
import com.example.agentweb.app.requirement.RequirementProperties;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementNotFoundException;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作区编排（detailed-design §2.6）：校验 → provision → 建聚合 save → 租约 →
 * requirement.attachWorkspace 回写。业务规则全在聚合/策略，此处只做顺序与事务。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class WorkspaceAppService {

    private final RequirementRepository requirementRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PortLeaseStore portLeaseStore;
    private final WorkspaceProvisioner provisioner;
    private final RequirementProperties properties;

    public WorkspaceAppService(RequirementRepository requirementRepository,
                               WorkspaceRepository workspaceRepository,
                               PortLeaseStore portLeaseStore,
                               WorkspaceProvisioner provisioner,
                               RequirementProperties properties) {
        this.requirementRepository = requirementRepository;
        this.workspaceRepository = workspaceRepository;
        this.portLeaseStore = portLeaseStore;
        this.provisioner = provisioner;
        this.properties = properties;
    }

    /**
     * 为已审批需求供给工作区，幂等：已有未释放工作区直接复用。
     *
     * @return workspaceId
     */
    @Transactional
    public String provisionFor(String requirementId) {
        Requirement requirement = loadRequirement(requirementId);
        RequirementWorkspace existing = workspaceRepository.findByRequirementId(requirementId);
        if (existing != null && existing.isReusable()) {
            return existing.getId().getValue();
        }

        requirement.assertWorkspaceAttachable();
        RequirementProperties.Workspace config = properties.getWorkspace();
        ProvisionedWorkspace provisioned = provisioner.provision(new ProvisionRequest(
                config.getRepoUrl(), requirementId,
                RequirementWorkspace.branchFor(requirementId), null));

        RequirementWorkspace workspace = RequirementWorkspace.create(requirementId,
                config.getRepoUrl(), provisioned.getMirrorPath(), provisioned.getWorktreePath(),
                config.getTtlHours());
        workspace.markReady();
        workspaceRepository.save(workspace);
        int port = portLeaseStore.allocate(workspace.getId().getValue());

        requirement.attachWorkspace(workspace.getId().getValue());
        requirementRepository.update(requirement);
        log.info("workspace-provisioned requirementId={} workspaceId={} port={} worktree={}",
                requirementId, workspace.getId().getValue(), port, provisioned.getWorktreePath());
        return workspace.getId().getValue();
    }

    private Requirement loadRequirement(String requirementId) {
        Requirement requirement = requirementRepository.findById(requirementId);
        if (requirement == null) {
            throw new RequirementNotFoundException(requirementId);
        }
        return requirement;
    }
}
