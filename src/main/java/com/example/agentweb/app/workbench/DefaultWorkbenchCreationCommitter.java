package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchCreationReceipt;
import com.example.agentweb.domain.workbench.WorkbenchCreationRepository;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 在一个事务内重检幂等收据并保存创建期强一致事实。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class DefaultWorkbenchCreationCommitter implements WorkbenchCreationCommitter {

    private final WorkbenchCreationRepository creationRepository;
    private final WorkbenchRepository workbenchRepository;
    private final WorkspaceSnapshotRepository snapshotRepository;
    private final WorkbenchCreationTransaction transaction;

    public DefaultWorkbenchCreationCommitter(
            WorkbenchCreationRepository creationRepository,
            WorkbenchRepository workbenchRepository,
            WorkspaceSnapshotRepository snapshotRepository,
            WorkbenchCreationTransaction transaction) {
        this.creationRepository = creationRepository;
        this.workbenchRepository = workbenchRepository;
        this.snapshotRepository = snapshotRepository;
        this.transaction = transaction;
    }

    @Override
    public WorkbenchCreationResult commit(final PreparedWorkbenchCreation creation) {
        if (creation == null) {
            throw new IllegalArgumentException("prepared workbench creation is required");
        }
        return transaction.execute(() -> commitInTransaction(creation));
    }

    private WorkbenchCreationResult commitInTransaction(
            PreparedWorkbenchCreation creation) {
        WorkbenchCreationReceipt candidate = creation.getReceipt();
        Optional<WorkbenchCreationReceipt> existing =
                creationRepository.findByOwnerAndIdempotencyKey(
                        candidate.getOwner(), candidate.getIdempotencyKey());
        if (existing.isPresent()) {
            WorkbenchId replayedId = existing.get().requireReplay(
                    candidate.getOwner(), candidate.getIdempotencyKey(),
                    candidate.getRequestHash());
            return WorkbenchCreationResult.replayed(requireWorkbench(replayedId));
        }
        snapshotRepository.add(creation.getSnapshot());
        workbenchRepository.add(creation.getWorkbench());
        creationRepository.add(candidate);
        return WorkbenchCreationResult.created(creation.getWorkbench());
    }

    private Workbench requireWorkbench(WorkbenchId workbenchId) {
        return workbenchRepository.findById(workbenchId)
                .orElseThrow(() -> new IllegalStateException(
                        "workbench creation receipt points to a missing workbench"));
    }
}
