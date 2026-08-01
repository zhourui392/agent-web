package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 从不可变 Workbench Run Snapshot 解析 Handoff 安全引用。
 *
 * <p>MVP 的 safeSummary 只使用 Run ID 与 Phase 元数据，不复制 Prompt、
 * Tool Output 或执行结果正文。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public class DefaultHandoffRunReferenceResolver
        implements HandoffRunReferenceResolver {

    private final WorkbenchRunSnapshotRepository snapshotRepository;

    public DefaultHandoffRunReferenceResolver(
            WorkbenchRunSnapshotRepository snapshotRepository) {
        this.snapshotRepository = Objects.requireNonNull(
                snapshotRepository, "snapshotRepository");
    }

    @Override
    public List<WorkbenchRunReference> requireReferences(
            List<String> runIds) {
        Objects.requireNonNull(runIds, "runIds");
        List<WorkbenchRunReference> references =
                new ArrayList<WorkbenchRunReference>(runIds.size());
        for (String runId : runIds) {
            WorkbenchRunSnapshot snapshot = snapshotRepository
                    .findByRunId(runId)
                    .orElseThrow(() -> invalidRunReference(runId));
            references.add(WorkbenchRunReference.of(
                    snapshot.getRunId(), snapshot.getWorkbenchId(),
                    snapshot.getPhase(), safeSummary(snapshot)));
        }
        return Collections.unmodifiableList(references);
    }

    private String safeSummary(WorkbenchRunSnapshot snapshot) {
        return "Run " + snapshot.getRunId()
                + " (" + snapshot.getPhase().name() + ")";
    }

    private HandoffApplicationException invalidRunReference(String runId) {
        return new HandoffApplicationException(
                HandoffApplicationErrorCode.RUN_REFERENCE_INVALID,
                "referenced workbench run was not found");
    }
}
