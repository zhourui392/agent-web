package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.WorkbenchPhaseLifecycleResult;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchPhaseStatus;
import lombok.Getter;

/**
 * Workbench Phase 人工状态或会话代际变更响应。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchPhaseLifecycleResponse {

    private final String workbenchId;
    private final WorkbenchPhase phase;
    private final WorkbenchPhaseStatus phaseStatus;
    private final String conversationId;
    private final int conversationGeneration;
    private final long workbenchVersion;
    private final boolean changed;

    private WorkbenchPhaseLifecycleResponse(
            String workbenchId, WorkbenchPhase phase,
            WorkbenchPhaseStatus phaseStatus, String conversationId,
            int conversationGeneration, long workbenchVersion,
            boolean changed) {
        this.workbenchId = workbenchId;
        this.phase = phase;
        this.phaseStatus = phaseStatus;
        this.conversationId = conversationId;
        this.conversationGeneration = conversationGeneration;
        this.workbenchVersion = workbenchVersion;
        this.changed = changed;
    }

    public static WorkbenchPhaseLifecycleResponse from(
            WorkbenchPhaseLifecycleResult result) {
        return new WorkbenchPhaseLifecycleResponse(
                result.getWorkbenchId(), result.getPhase(), result.getPhaseStatus(),
                result.getConversationId(), result.getConversationGeneration(),
                result.getWorkbenchVersion(), result.isChanged());
    }
}
