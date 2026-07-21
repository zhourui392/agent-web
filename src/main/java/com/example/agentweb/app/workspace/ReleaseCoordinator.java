package com.example.agentweb.app.workspace;

import com.example.agentweb.adapter.workspace.ReleaseRequest;
import com.example.agentweb.adapter.workspace.WorkspaceProvisioner;
import com.example.agentweb.domain.workspace.DirtyReport;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资源释放编排雏形（master-plan §6.5 回收矩阵）：worktree + 端口租约随工作区释放，
 * M2 起交付线追加 MR/分支清理。释放不变量（dirty×force）由聚合 assertReleasable 持有。
 * M1 一律保留 req/* 分支（removeBranch=false），删分支决策随 M2 交付终态接入。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class ReleaseCoordinator {

    private final WorkspaceProvisioner provisioner;
    private final WorkspaceRepository workspaceRepository;
    private final PortLeaseStore portLeaseStore;

    public ReleaseCoordinator(WorkspaceProvisioner provisioner,
                              WorkspaceRepository workspaceRepository,
                              PortLeaseStore portLeaseStore) {
        this.provisioner = provisioner;
        this.workspaceRepository = workspaceRepository;
        this.portLeaseStore = portLeaseStore;
    }

    @Transactional
    public void release(RequirementWorkspace workspace, DirtyReport report, boolean force) {
        workspace.assertReleasable(report, force);
        provisioner.release(new ReleaseRequest(workspace.getMirrorPath(),
                workspace.getWorktreePath(), workspace.getBranch(), force, false));
        portLeaseStore.releaseAll(workspace.getId().getValue());
        workspace.markReleased();
        workspaceRepository.save(workspace);
        log.info("workspace-released workspaceId={} requirementId={} force={}",
                workspace.getId().getValue(), workspace.getRequirementId(), force);
    }
}
