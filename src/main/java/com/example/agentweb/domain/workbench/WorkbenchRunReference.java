package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Objects;

/**
 * Handoff 对已核验 Workbench Run 的安全引用，不复制完整输出。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunReference implements Comparable<WorkbenchRunReference> {

    private final String runId;
    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final String safeSummary;

    private WorkbenchRunReference(String runId, WorkbenchId workbenchId,
                                  WorkbenchPhase phase, String safeSummary) {
        this.runId = DomainText.require(runId, "referenced run id", 128);
        if (workbenchId == null || phase == null) {
            throw new IllegalArgumentException(
                    "referenced run workbench and phase are required");
        }
        this.workbenchId = workbenchId;
        this.phase = phase;
        this.safeSummary = WorkbenchText.requireUntrustedText(
                safeSummary, "referenced run safe summary", 1000);
    }

    public static WorkbenchRunReference of(String runId, WorkbenchId workbenchId,
                                           WorkbenchPhase phase, String safeSummary) {
        return new WorkbenchRunReference(runId, workbenchId, phase, safeSummary);
    }

    @Override
    public int compareTo(WorkbenchRunReference other) {
        return runId.compareTo(other.runId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkbenchRunReference)) {
            return false;
        }
        WorkbenchRunReference that = (WorkbenchRunReference) other;
        return runId.equals(that.runId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId);
    }
}
