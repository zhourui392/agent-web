package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.WorkbenchCreationResult;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import lombok.Getter;

/**
 * Workbench 创建或幂等重放响应。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchCreationResponse {

    private final String workbenchId;
    private final WorkbenchStatus status;
    private final long version;
    private final boolean replayed;

    private WorkbenchCreationResponse(
            String workbenchId, WorkbenchStatus status, long version, boolean replayed) {
        this.workbenchId = workbenchId;
        this.status = status;
        this.version = version;
        this.replayed = replayed;
    }

    public static WorkbenchCreationResponse from(WorkbenchCreationResult result) {
        return new WorkbenchCreationResponse(
                result.getWorkbenchId(), result.getStatus(), result.getVersion(),
                result.isReplayed());
    }
}
