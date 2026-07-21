package com.example.agentweb.app.workspace;

import com.example.agentweb.adapter.workspace.ReleaseRequest;
import com.example.agentweb.adapter.workspace.WorkspaceProvisioner;
import com.example.agentweb.app.requirement.RequirementProperties;
import com.example.agentweb.domain.requirement.AgentPlan;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.domain.workspace.DirtyReport;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceDirtyException;
import com.example.agentweb.domain.workspace.WorkspaceId;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import com.example.agentweb.domain.workspace.WorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TTL 清理编排单测：保留策略过滤、dirty 跳过、clean 释放、单个失败不阻断整轮。
 * 释放动作经真实 ReleaseCoordinator（Mock infra 端口），保证 assertReleasable 不被 mock 掉。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@ExtendWith(MockitoExtension.class)
public class WorkspaceCleanupServiceTest {

    private static final String OWNER = "V33215020";

    @Mock
    private WorkspaceRepository workspaceRepository;
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private WorkspaceProvisioner provisioner;
    @Mock
    private PortLeaseStore portLeaseStore;
    @Mock
    private com.example.agentweb.app.delivery.WebhookDedupStore webhookDedupStore;
    @Mock
    private com.example.agentweb.adapter.NotificationGateway notificationGateway;

    private WorkspaceCleanupService cleanupService;

    @BeforeEach
    public void setUp() {
        ReleaseCoordinator releaseCoordinator = new ReleaseCoordinator(
                provisioner, workspaceRepository, portLeaseStore);
        cleanupService = new WorkspaceCleanupService(workspaceRepository, requirementRepository,
                provisioner, releaseCoordinator, new RequirementProperties(),
                provider(webhookDedupStore), provider(notificationGateway));
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<T> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private RequirementWorkspace expiredWorkspace(String requirementId) {
        return new RequirementWorkspace(WorkspaceId.newId(requirementId), requirementId,
                "http://git/repo.git", "m", "w-" + requirementId, "req/" + requirementId,
                WorkspaceStatus.READY, 72, Instant.now().minus(100, ChronoUnit.HOURS));
    }

    private Requirement suspendedRequirement() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", null, OWNER);
        requirement.suspend(OWNER, "等依赖");
        return requirement;
    }

    private Requirement approvedRequirement() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", null, OWNER);
        requirement.attachPlan(new AgentPlan("p", null, null, Instant.now()), OWNER);
        requirement.approve(OWNER);
        return requirement;
    }

    @Test
    public void retained_requirement_status_should_skip_release() {
        RequirementWorkspace workspace = expiredWorkspace("R2607040001");
        when(workspaceRepository.findIdleBefore(any())).thenReturn(List.of(workspace));
        when(requirementRepository.findById("R2607040001")).thenReturn(suspendedRequirement());

        cleanupService.cleanupExpired();

        verify(provisioner, never()).release(any());
        assertEquals(WorkspaceStatus.READY, workspace.getStatus());
    }

    @Test
    public void dirty_workspace_should_skip_release_and_wait_notification() {
        RequirementWorkspace workspace = expiredWorkspace("R2607040001");
        when(workspaceRepository.findIdleBefore(any())).thenReturn(List.of(workspace));
        when(requirementRepository.findById("R2607040001")).thenReturn(approvedRequirement());
        when(provisioner.detectDirty(workspace.getWorktreePath()))
                .thenReturn(new DirtyReport(List.of("a.java"), 0));

        cleanupService.cleanupExpired();

        verify(provisioner, never()).release(any());
        verify(portLeaseStore, never()).releaseAll(anyString());
        // M2.5: dirty 跳过时通知属主自行处置
        verify(notificationGateway).notifyOwner(org.mockito.ArgumentMatchers.eq(OWNER),
                any(com.example.agentweb.adapter.OwnerNotice.class));
    }

    @Test
    public void cleanup_round_should_purge_stale_webhook_dedup_rows() {
        when(workspaceRepository.findIdleBefore(any())).thenReturn(List.of());

        cleanupService.cleanupExpired();

        // §3.8: processed_webhook 随 cleanup cron 顺带清 30 天前旧行
        verify(webhookDedupStore).purgeBefore(any(Instant.class));
    }

    @Test
    public void webhook_purge_failure_should_not_break_round() {
        when(workspaceRepository.findIdleBefore(any())).thenReturn(List.of());
        when(webhookDedupStore.purgeBefore(any())).thenThrow(new IllegalStateException("db locked"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> cleanupService.cleanupExpired());
    }

    @Test
    public void clean_expired_workspace_should_release_worktree_ports_and_mark_released() {
        RequirementWorkspace workspace = expiredWorkspace("R2607040001");
        when(workspaceRepository.findIdleBefore(any())).thenReturn(List.of(workspace));
        when(requirementRepository.findById("R2607040001")).thenReturn(approvedRequirement());
        when(provisioner.detectDirty(workspace.getWorktreePath())).thenReturn(DirtyReport.clean());

        cleanupService.cleanupExpired();

        verify(provisioner).release(any());
        verify(portLeaseStore).releaseAll(workspace.getId().getValue());
        verify(workspaceRepository).save(workspace);
        assertEquals(WorkspaceStatus.RELEASED, workspace.getStatus());
    }

    @Test
    public void orphan_workspace_without_requirement_should_still_release() {
        RequirementWorkspace workspace = expiredWorkspace("R2607040001");
        when(workspaceRepository.findIdleBefore(any())).thenReturn(List.of(workspace));
        when(requirementRepository.findById("R2607040001")).thenReturn(null);
        when(provisioner.detectDirty(anyString())).thenReturn(DirtyReport.clean());

        cleanupService.cleanupExpired();

        assertEquals(WorkspaceStatus.RELEASED, workspace.getStatus());
    }

    @Test
    public void one_failure_should_not_break_the_whole_round() {
        RequirementWorkspace broken = expiredWorkspace("R2607040001");
        RequirementWorkspace healthy = expiredWorkspace("R2607040002");
        when(workspaceRepository.findIdleBefore(any())).thenReturn(List.of(broken, healthy));
        when(requirementRepository.findById(anyString())).thenReturn(approvedRequirement());
        when(provisioner.detectDirty(broken.getWorktreePath()))
                .thenThrow(new IllegalStateException("git 崩了"));
        when(provisioner.detectDirty(healthy.getWorktreePath())).thenReturn(DirtyReport.clean());

        cleanupService.cleanupExpired();

        assertEquals(WorkspaceStatus.RELEASED, healthy.getStatus());
        assertEquals(WorkspaceStatus.READY, broken.getStatus());
    }

    @Test
    public void release_dirty_without_force_should_not_touch_any_resource() {
        ReleaseCoordinator coordinator = new ReleaseCoordinator(
                provisioner, workspaceRepository, portLeaseStore);
        RequirementWorkspace workspace = expiredWorkspace("R2607040001");
        DirtyReport dirty = new DirtyReport(List.of("a.java"), 1);

        assertThrows(WorkspaceDirtyException.class,
                () -> coordinator.release(workspace, dirty, false));

        verify(provisioner, never()).release(any());
        verify(portLeaseStore, never()).releaseAll(anyString());
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    public void release_dirty_with_force_should_pass_force_to_provisioner() {
        ReleaseCoordinator coordinator = new ReleaseCoordinator(
                provisioner, workspaceRepository, portLeaseStore);
        RequirementWorkspace workspace = expiredWorkspace("R2607040001");

        coordinator.release(workspace, new DirtyReport(List.of("a.java"), 1), true);

        ArgumentCaptor<ReleaseRequest> captor = ArgumentCaptor.forClass(ReleaseRequest.class);
        verify(provisioner).release(captor.capture());
        assertTrue(captor.getValue().isForce());
        assertEquals(WorkspaceStatus.RELEASED, workspace.getStatus());
    }
}
