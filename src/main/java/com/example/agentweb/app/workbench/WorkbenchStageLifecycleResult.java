package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationReference;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageStatus;
import lombok.Getter;

/**
 * 动态 Stage 人工状态或会话代际变更后的轻量响应。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageLifecycleResult {

    private final String workbenchId;
    private final String stageInstanceIdentifier;
    private final String definitionIdentifier;
    private final WorkbenchStageStatus stageStatus;
    private final String conversationId;
    private final int conversationGeneration;
    private final long workbenchVersion;
    private final boolean changed;

    private WorkbenchStageLifecycleResult(
            Workbench workbench, String stageInstanceIdentifier, boolean changed) {
        if (workbench == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage lifecycle result is required");
        }
        WorkbenchStageState stage = workbench.stage(stageInstanceIdentifier);
        this.workbenchId = workbench.getId().getValue();
        this.stageInstanceIdentifier = stage.getStageInstanceIdentifier();
        this.definitionIdentifier = stage.getSnapshot().getDefinitionIdentifier();
        this.stageStatus = stage.getStatus();
        WorkbenchStageConversationReference currentConversation =
                stage.currentConversation();
        this.conversationId = currentConversation == null
                ? null : currentConversation.getConversationId();
        this.conversationGeneration = stage.getConversationGeneration();
        this.workbenchVersion = workbench.getVersion();
        this.changed = changed;
    }

    public static WorkbenchStageLifecycleResult from(
            Workbench workbench, String stageInstanceIdentifier, boolean changed) {
        return new WorkbenchStageLifecycleResult(
                workbench, stageInstanceIdentifier, changed);
    }
}
