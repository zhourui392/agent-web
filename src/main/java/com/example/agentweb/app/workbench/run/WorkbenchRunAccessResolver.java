package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshotRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 以 Owner-first 顺序加载并验证 Workbench Run 的应用授权边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
class WorkbenchRunAccessResolver {

    private final WorkbenchRepository workbenchRepository;
    private final WorkbenchStageRunSnapshotRepository snapshotRepository;
    private final ChatRunRepository runRepository;

    WorkbenchRunAccessResolver(
            WorkbenchRepository workbenchRepository,
            WorkbenchStageRunSnapshotRepository snapshotRepository,
            ChatRunRepository runRepository) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.snapshotRepository = Objects.requireNonNull(
                snapshotRepository, "snapshotRepository");
        this.runRepository = Objects.requireNonNull(
                runRepository, "runRepository");
    }

    AuthorizedWorkbenchRun requireAuthorized(
            OwnerReference actor, WorkbenchId workbenchId,
            String runIdValue) {
        Workbench workbench = requireOwned(actor, workbenchId);

        ChatRunId runId = parseRunId(runIdValue);
        try {
            WorkbenchStageRunSnapshot snapshot = snapshotRepository
                    .findByRunId(runId.getValue())
                    .orElseThrow(WorkbenchRunNotFoundException::new);
            ChatRun run = runRepository.findById(runId)
                    .orElseThrow(WorkbenchRunNotFoundException::new);
            snapshot.requireExactRun(
                    workbench, run, runId.getValue());
            return AuthorizedWorkbenchRun.verified(
                    workbench, snapshot, run);
        } catch (ChatRunNotFoundException failure) {
            throw new WorkbenchRunNotFoundException();
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode()
                    != WorkbenchErrorCode.RUN_BINDING_CORRUPTED) {
                throw failure;
            }
            throw new WorkbenchRunNotFoundException();
        }
    }

    Workbench requireOwned(
            OwnerReference actor, WorkbenchId workbenchId) {
        if (actor == null || workbenchId == null) {
            throw new IllegalArgumentException(
                    "workbench run actor and workbench id are required");
        }
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(WorkbenchRunNotFoundException::new);
        requireOwner(workbench, actor);
        return workbench;
    }

    private ChatRunId parseRunId(String value) {
        try {
            if (value == null || value.trim().length() > 128) {
                throw new IllegalArgumentException(
                        "workbench run id is invalid");
            }
            return ChatRunId.of(value);
        } catch (IllegalArgumentException failure) {
            throw new WorkbenchRunNotFoundException();
        }
    }

    private void requireOwner(
            Workbench workbench, OwnerReference actor) {
        try {
            workbench.requireOwnedBy(actor);
        } catch (WorkbenchDomainException failure) {
            if (failure.getCode() == WorkbenchErrorCode.OWNER_REQUIRED) {
                throw new WorkbenchRunNotFoundException();
            }
            throw failure;
        }
    }
}
