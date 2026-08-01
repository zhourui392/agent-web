package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final WorkbenchRepository workbenchRepository;
    private final Clock clock;

    public WorkbenchLifecycleAppService(
            WorkbenchRepository workbenchRepository, Clock clock) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkbenchLifecycleResult load(
            OwnerReference actor, WorkbenchId workbenchId) {
        return WorkbenchLifecycleResult.observed(
                requireOwnedWorkbench(actor, workbenchId));
    }

    @Transactional
    public WorkbenchPhaseLifecycleResult completePhase(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase, long expectedVersion) {
        return mutatePhase(actor, workbenchId, phase, expectedVersion,
                (workbench, now) -> workbench.completePhase(phase, actor, now));
    }

    @Transactional
    public WorkbenchPhaseLifecycleResult reopenPhase(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase, long expectedVersion) {
        return mutatePhase(actor, workbenchId, phase, expectedVersion,
                (workbench, now) -> workbench.reopenPhase(phase, actor, now));
    }

    @Transactional
    public WorkbenchLifecycleResult archive(
            OwnerReference actor, WorkbenchId workbenchId, long expectedVersion) {
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        requireExpectedVersion(workbench, expectedVersion);
        boolean changed = workbench.archive(actor, clock.instant());
        updateWhenChanged(workbench, changed);
        return WorkbenchLifecycleResult.afterMutation(workbench, changed);
    }

    @Transactional
    public WorkbenchPhaseLifecycleResult bindConversation(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase, String conversationId, long expectedVersion) {
        return mutatePhase(actor, workbenchId, phase, expectedVersion,
                (workbench, now) -> workbench.bindConversation(
                        phase, conversationId, actor, now));
    }

    @Transactional
    public WorkbenchPhaseLifecycleResult restartConversation(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase, String conversationId, long expectedVersion) {
        return mutatePhase(actor, workbenchId, phase, expectedVersion,
                (workbench, now) -> workbench.restartConversation(
                        phase, conversationId, actor, now));
    }

    private WorkbenchPhaseLifecycleResult mutatePhase(
            OwnerReference actor, WorkbenchId workbenchId,
            WorkbenchPhase phase, long expectedVersion,
            PhaseMutation mutation) {
        if (phase == null || mutation == null) {
            throw new IllegalArgumentException(
                    "workbench phase lifecycle operation is required");
        }
        Workbench workbench = requireOwnedWorkbench(actor, workbenchId);
        requireExpectedVersion(workbench, expectedVersion);
        boolean changed = mutation.apply(workbench, clock.instant());
        updateWhenChanged(workbench, changed);
        return WorkbenchPhaseLifecycleResult.from(workbench, phase, changed);
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

    private void requireExpectedVersion(Workbench workbench, long expectedVersion) {
        if (expectedVersion < 0L) {
            throw new IllegalArgumentException(
                    "expected workbench version must not be negative");
        }
        if (workbench.getVersion() != expectedVersion) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "stale workbench version");
        }
    }

    private void updateWhenChanged(Workbench workbench, boolean changed) {
        if (changed) {
            workbenchRepository.update(workbench);
        }
    }

    @FunctionalInterface
    private interface PhaseMutation {

        boolean apply(Workbench workbench, Instant now);
    }
}
