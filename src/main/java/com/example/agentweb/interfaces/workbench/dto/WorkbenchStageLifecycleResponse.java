package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.WorkbenchStageLifecycleResult;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageStatus;
import lombok.Getter;

/**
 * Workbench 动态 Stage 人工状态或会话代际变更响应。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageLifecycleResponse {

    private final String workbenchId;
    private final String stageInstanceIdentifier;
    private final String definitionIdentifier;
    private final WorkbenchStageStatus stageStatus;
    private final String conversationId;
    private final int conversationGeneration;
    private final long workbenchVersion;
    private final boolean changed;

    private WorkbenchStageLifecycleResponse(
            String workbenchId, String stageInstanceIdentifier,
            String definitionIdentifier, WorkbenchStageStatus stageStatus,
            String conversationId, int conversationGeneration,
            long workbenchVersion, boolean changed) {
        this.workbenchId = workbenchId;
        this.stageInstanceIdentifier = stageInstanceIdentifier;
        this.definitionIdentifier = definitionIdentifier;
        this.stageStatus = stageStatus;
        this.conversationId = conversationId;
        this.conversationGeneration = conversationGeneration;
        this.workbenchVersion = workbenchVersion;
        this.changed = changed;
    }

    public static WorkbenchStageLifecycleResponse from(
            WorkbenchStageLifecycleResult result) {
        return new WorkbenchStageLifecycleResponse(
                result.getWorkbenchId(), result.getStageInstanceIdentifier(),
                result.getDefinitionIdentifier(), result.getStageStatus(),
                result.getConversationId(), result.getConversationGeneration(),
                result.getWorkbenchVersion(), result.isChanged());
    }
}
