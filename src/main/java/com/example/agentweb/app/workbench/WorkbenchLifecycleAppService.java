package com.example.agentweb.app.workbench;

import com.example.agentweb.app.workbench.port.WorkbenchWorktreeGateway;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Workbench 人工生命周期与阶段会话应用编排。
 *
 * <p>所有合法转换和幂等判断均委托 Workbench 聚合；本服务只负责 Owner 范围加载、
 * 调用方乐观版本、时钟、事务和 Repository 更新。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
@Transactional(readOnly = true)
public class WorkbenchLifecycleAppService {

    private static final Logger log = LoggerFactory.getLogger(
            WorkbenchLifecycleAppService.class);

    private final WorkbenchRepository workbenchRepository;
    private final WorkbenchWorktreeGateway worktreeGateway;
    private final Clock clock;

    public WorkbenchLifecycleAppService(
            WorkbenchRepository workbenchRepository,
            WorkbenchWorktreeGateway worktreeGateway, Clock clock) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.worktreeGateway = Objects.requireNonNull(
                worktreeGateway, "worktreeGateway");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkbenchLifecycleResult load(
            OwnerReference actor, WorkbenchId workbenchId) {
        return WorkbenchLifecycleResult.observed(
                requireOwnedWorkbench(actor, workbenchId));
    }

    @Transactional
    public WorkbenchStageLifecycleResult completeStage(
            OwnerReference actor, WorkbenchId workbenchId,
            String stageInstanceIdentifier, long expectedVersion) {
        return mutateStage(
                actor, workbenchId, stageInstanceIdentifier, expectedVersion,
                (workbench, version, now) -> workbench.completeStage(
                        stageInstanceIdentifier, actor, version, now));
    }

    @Transactional
    public WorkbenchStageLifecycleResult reopenStage(
            OwnerReference actor, WorkbenchId workbenchId,
            String stageInstanceIdentifier, long expectedVersion) {
        return mutateStage(
                actor, workbenchId, stageInstanceIdentifier, expectedVersion,
                (workbench, version, now) -> workbench.reopenStage(
                        stageInstanceIdentifier, actor, version, now));
    }

    @Transactional
    public WorkbenchLifecycleResult archive(
            OwnerReference actor, WorkbenchId workbenchId, long expectedVersion) {
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        boolean changed = workbench.archive(
                actor, expectedVersion, clock.instant());
        updateWhenChanged(workbench, changed);
        if (changed && workbench.isUseWorktree()) {
            cleanupWorktree(workbench);
        }
        return WorkbenchLifecycleResult.afterMutation(workbench, changed);
    }

    private void cleanupWorktree(Workbench workbench) {
        try {
            worktreeGateway.removeWorktree(
                    workbench.getRepositoryScope().primaryRepository()
                            .getRepositoryRoot(),
                    Paths.get(workbench.getWorktreePath()),
                    workbench.getWorktreeBranch());
        } catch (IOException | InterruptedException ex) {
            log.warn("worktree-cleanup-failed workbenchId={} reason={}",
                    workbench.getId().getValue(), ex.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    private WorkbenchStageLifecycleResult mutateStage(
            OwnerReference actor, WorkbenchId workbenchId,
            String stageInstanceIdentifier, long expectedVersion,
            StageMutation mutation) {
        if (mutation == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage lifecycle operation is required");
        }
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        boolean changed = mutation.apply(
                workbench, expectedVersion, clock.instant());
        updateWhenChanged(workbench, changed);
        return WorkbenchStageLifecycleResult.from(
                workbench, stageInstanceIdentifier, changed);
    }

    private Workbench requireOwnedWorkbench(
            OwnerReference actor, WorkbenchId workbenchId) {
        if (actor == null || workbenchId == null) {
            throw new IllegalArgumentException(
                    "workbench actor and id are required");
        }
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchNotFoundException::new);
        try {
            workbench.requireOwnedBy(actor);
            return workbench;
        } catch (WorkbenchDomainException ex) {
            if (ex.getCode() == WorkbenchErrorCode.OWNER_REQUIRED) {
                throw new WorkbenchNotFoundException();
            }
            throw ex;
        }
    }

    private void updateWhenChanged(Workbench workbench, boolean changed) {
        if (changed) {
            workbenchRepository.update(workbench);
        }
    }

    @FunctionalInterface
    private interface StageMutation {

        boolean apply(Workbench workbench, long expectedVersion, Instant now);
    }
}
