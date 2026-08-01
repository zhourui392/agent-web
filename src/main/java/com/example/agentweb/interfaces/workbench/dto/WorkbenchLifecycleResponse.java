package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.WorkbenchLifecycleResult;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import lombok.Getter;

/**
 * Workbench 人工生命周期变更响应。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchLifecycleResponse {

    private final String workbenchId;
    private final WorkbenchStatus status;
    private final long version;
    private final boolean changed;

    private WorkbenchLifecycleResponse(
            String workbenchId, WorkbenchStatus status, long version,
            boolean changed) {
        this.workbenchId = workbenchId;
        this.status = status;
        this.version = version;
        this.changed = changed;
    }

    public static WorkbenchLifecycleResponse from(
            WorkbenchLifecycleResult result) {
        return new WorkbenchLifecycleResponse(
                result.getWorkbenchId(), result.getStatus(), result.getVersion(),
                result.isChanged());
    }
}
