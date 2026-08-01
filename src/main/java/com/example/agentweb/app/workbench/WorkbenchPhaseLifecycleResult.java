package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.PhaseConversationReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchPhaseState;
import com.example.agentweb.domain.workbench.WorkbenchPhaseStatus;
import lombok.Getter;

/**
 * 阶段人工状态或会话代际变更后的轻量响应。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchPhaseLifecycleResult {

    private final String workbenchId;
    private final WorkbenchPhase phase;
    private final WorkbenchPhaseStatus phaseStatus;
    private final String conversationId;
    private final int conversationGeneration;
    private final long workbenchVersion;
    private final boolean changed;

    private WorkbenchPhaseLifecycleResult(
            Workbench workbench, WorkbenchPhase phase, boolean changed) {
        if (workbench == null || phase == null) {
            throw new IllegalArgumentException(
                    "workbench phase lifecycle result is required");
        }
        WorkbenchPhaseState state = workbench.phase(phase);
        PhaseConversationReference conversation = state.currentConversation();
        this.workbenchId = workbench.getId().getValue();
        this.phase = phase;
        this.phaseStatus = state.getStatus();
        this.conversationId = conversation == null ? null : conversation.getConversationId();
        this.conversationGeneration = state.getConversationGeneration();
        this.workbenchVersion = workbench.getVersion();
        this.changed = changed;
    }

    public static WorkbenchPhaseLifecycleResult from(
            Workbench workbench, WorkbenchPhase phase, boolean changed) {
        return new WorkbenchPhaseLifecycleResult(workbench, phase, changed);
    }
}
