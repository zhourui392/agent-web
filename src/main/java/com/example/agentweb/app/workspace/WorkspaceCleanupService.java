package com.example.agentweb.app.workspace;

import com.example.agentweb.adapter.NotificationGateway;
import com.example.agentweb.adapter.OwnerNotice;
import com.example.agentweb.adapter.workspace.WorkspaceProvisioner;
import com.example.agentweb.app.delivery.WebhookDedupStore;
import com.example.agentweb.app.requirement.RequirementProperties;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.workspace.DirtyReport;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import com.example.agentweb.domain.workspace.WorkspaceRetentionPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 工作区 TTL 清理调度（detailed-design §2.6）：候选查询 → 保留策略过滤（REVIEW/VERIFYING/
 * SUSPENDED 跳过）→ dirty 检测 → clean 才释放；dirty 跳过并通知属主（M2.5 接通道，失败只降级）。
 * 顺带清 processed_webhook 超 30 天旧行（§3.8）。单个失败不阻断整轮。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class WorkspaceCleanupService {

    private static final WorkspaceRetentionPolicy RETENTION_POLICY = new WorkspaceRetentionPolicy();

    /** processed_webhook 行保留期（§3.8 约定 30 天防无限增长）。 */
    private static final Duration WEBHOOK_DEDUP_RETENTION = Duration.ofDays(30);

    private final WorkspaceRepository workspaceRepository;
    private final RequirementRepository requirementRepository;
    private final WorkspaceProvisioner provisioner;
    private final ReleaseCoordinator releaseCoordinator;
    private final RequirementProperties properties;
    private final WebhookDedupStore webhookDedupStore;
    private final NotificationGateway notificationGateway;

    public WorkspaceCleanupService(WorkspaceRepository workspaceRepository,
                                   RequirementRepository requirementRepository,
                                   WorkspaceProvisioner provisioner,
                                   ReleaseCoordinator releaseCoordinator,
                                   RequirementProperties properties,
                                   ObjectProvider<WebhookDedupStore> webhookDedupStore,
                                   ObjectProvider<NotificationGateway> notificationGateway) {
        this.workspaceRepository = workspaceRepository;
        this.requirementRepository = requirementRepository;
        this.provisioner = provisioner;
        this.releaseCoordinator = releaseCoordinator;
        this.properties = properties;
        this.webhookDedupStore = webhookDedupStore.getIfAvailable();
        this.notificationGateway = notificationGateway.getIfAvailable();
    }

    @Scheduled(cron = "${agent.requirement.workspace.cleanup-cron:0 40 4 * * *}")
    public void cleanupExpired() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofHours(properties.getWorkspace().getTtlHours()));
        List<RequirementWorkspace> candidates = workspaceRepository.findIdleBefore(cutoff);
        for (RequirementWorkspace workspace : candidates) {
            try {
                cleanupOne(workspace, now);
            } catch (Exception e) {
                log.warn("workspace-cleanup-failed workspaceId={}", workspace.getId().getValue(), e);
            }
        }
        purgeWebhookDedup(now);
    }

    private void purgeWebhookDedup(Instant now) {
        if (webhookDedupStore == null) {
            return;
        }
        try {
            int purged = webhookDedupStore.purgeBefore(now.minus(WEBHOOK_DEDUP_RETENTION));
            if (purged > 0) {
                log.info("webhook-dedup-purged rows={}", purged);
            }
        } catch (RuntimeException e) {
            log.warn("webhook-dedup-purge-failed reason={}", e.getMessage(), e);
        }
    }

    private void cleanupOne(RequirementWorkspace workspace, Instant now) {
        if (!workspace.isExpired(now)) {
            return;
        }
        Requirement requirement = requirementRepository.findById(workspace.getRequirementId());
        if (requirement != null && !RETENTION_POLICY.eligibleForCleanup(requirement.getStatus())) {
            log.info("workspace-retained workspaceId={} requirementStatus={}",
                    workspace.getId().getValue(), requirement.getStatus());
            return;
        }
        DirtyReport report = provisioner.detectDirty(workspace.getWorktreePath());
        if (report.isDirty()) {
            log.warn("workspace-dirty-skip-cleanup workspaceId={} uncommitted={} unpushed={}",
                    workspace.getId().getValue(), report.getUncommittedFiles().size(),
                    report.getUnpushedCommits());
            notifyDirtyOwnerSafely(workspace, requirement, report);
            return;
        }
        releaseCoordinator.release(workspace, report, false);
    }

    /** dirty 工作区通知属主自行处置（M2.5），通知是旁路：通道缺席或发送失败都只降级。 */
    private void notifyDirtyOwnerSafely(RequirementWorkspace workspace, Requirement requirement,
                                        DirtyReport report) {
        if (notificationGateway == null || requirement == null) {
            return;
        }
        try {
            notificationGateway.notifyOwner(requirement.getOwner(), new OwnerNotice(
                    workspace.getRequirementId(), "工作区过期但有未交付改动",
                    "未提交文件 " + report.getUncommittedFiles().size()
                            + " 个 / 未推送提交 " + report.getUnpushedCommits()
                            + " 个，已跳过自动清理，请处理后手动释放: " + workspace.getWorktreePath()));
        } catch (RuntimeException e) {
            log.warn("workspace-dirty-notify-failed workspaceId={} reason={}",
                    workspace.getId().getValue(), e.getMessage(), e);
        }
    }
}
