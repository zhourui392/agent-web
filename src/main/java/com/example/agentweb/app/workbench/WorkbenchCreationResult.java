package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import lombok.Getter;

/**
 * Workbench 创建或幂等重放后的轻量响应。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchCreationResult {

    private final String workbenchId;
    private final WorkbenchStatus status;
    private final long version;
    private final boolean replayed;

    private WorkbenchCreationResult(Workbench workbench, boolean replayed) {
        if (workbench == null) {
            throw new IllegalArgumentException("workbench creation result is required");
        }
        this.workbenchId = workbench.getId().getValue();
        this.status = workbench.getStatus();
        this.version = workbench.getVersion();
        this.replayed = replayed;
    }

    public static WorkbenchCreationResult created(Workbench workbench) {
        return new WorkbenchCreationResult(workbench, false);
    }

    public static WorkbenchCreationResult replayed(Workbench workbench) {
        return new WorkbenchCreationResult(workbench, true);
    }
}
