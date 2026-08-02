package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 不借用 Owner 身份的 Admin Workbench Run exact-binding 加载器。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
final class AdminWorkbenchRunAccessResolver {

    private final WorkbenchRepository workbenchRepository;
    private final WorkbenchRunSnapshotRepository snapshotRepository;
    private final ChatRunRepository runRepository;

    AdminWorkbenchRunAccessResolver(
            WorkbenchRepository workbenchRepository,
            WorkbenchRunSnapshotRepository snapshotRepository,
            ChatRunRepository runRepository) {
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.snapshotRepository = Objects.requireNonNull(
                snapshotRepository, "snapshotRepository");
        this.runRepository = Objects.requireNonNull(
                runRepository, "runRepository");
    }

    AdminControlledWorkbenchRun requireExact(
            WorkbenchId workbenchId, String runIdValue) {
        if (workbenchId == null) {
            throw new AdminWorkbenchRunNotFoundException();
        }
        ChatRunId runId = parseRunId(runIdValue);
        Workbench workbench = workbenchRepository.findById(workbenchId)
                .orElseThrow(AdminWorkbenchRunNotFoundException::new);
        WorkbenchRunSnapshot snapshot = snapshotRepository
                .findByRunId(runId.getValue())
                .orElseThrow(AdminWorkbenchRunNotFoundException::new);
        ChatRun run = runRepository.findById(runId)
                .orElseThrow(AdminWorkbenchRunNotFoundException::new);
        try {
            snapshot.requireExactRun(workbench, run, runId.getValue());
        } catch (RuntimeException failure) {
            throw new AdminWorkbenchRunNotFoundException();
        }
        return AdminControlledWorkbenchRun.verified(
                workbench, snapshot, run);
    }

    private ChatRunId parseRunId(String value) {
        try {
            if (value == null || value.trim().isEmpty()
                    || value.length() > 128) {
                throw new IllegalArgumentException("invalid run id");
            }
            return ChatRunId.of(value);
        } catch (IllegalArgumentException failure) {
            throw new AdminWorkbenchRunNotFoundException();
        }
    }
}
