package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import lombok.Getter;

/**
 * Workbench 加载或归档后的轻量生命周期响应。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchLifecycleResult {

    private final String workbenchId;
    private final WorkbenchStatus status;
    private final long version;
    private final boolean changed;

    private WorkbenchLifecycleResult(Workbench workbench, boolean changed) {
        if (workbench == null) {
            throw new IllegalArgumentException("workbench lifecycle result is required");
        }
        this.workbenchId = workbench.getId().getValue();
        this.status = workbench.getStatus();
        this.version = workbench.getVersion();
        this.changed = changed;
    }

    public static WorkbenchLifecycleResult observed(Workbench workbench) {
        return new WorkbenchLifecycleResult(workbench, false);
    }

    public static WorkbenchLifecycleResult afterMutation(
            Workbench workbench, boolean changed) {
        return new WorkbenchLifecycleResult(workbench, changed);
    }
}
