package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.shared.AgentType;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * Immutable read model containing the persisted inputs needed by a background run.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class ChatRunExecutionContext {

    private final String runId;
    private final String sessionId;
    private final long userMessageId;
    private final AgentType agentType;
    private final String workingDir;
    private final String resumeId;
    private final String env;
    private final String userId;
    private final String message;
    private final boolean recallEnabled;
    private final List<ChatRunHistoryMessageView> history;
    private final com.example.agentweb.domain.mode.ModeSnapshot modeSnapshot;
    private final String handoffFilePath;

    public ChatRunExecutionContext(String runId, String sessionId, long userMessageId,
                                   AgentType agentType, String workingDir, String resumeId,
                                   String env, String userId, String message, boolean recallEnabled,
                                   List<ChatRunHistoryMessageView> history) {
        this(runId, sessionId, userMessageId, agentType, workingDir, resumeId,
                env, userId, message, recallEnabled, history, null, null);
    }

    public ChatRunExecutionContext(String runId, String sessionId, long userMessageId,
                                   AgentType agentType, String workingDir, String resumeId,
                                   String env, String userId, String message, boolean recallEnabled,
                                   List<ChatRunHistoryMessageView> history,
                                   com.example.agentweb.domain.mode.ModeSnapshot modeSnapshot,
                                   String handoffFilePath) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.userMessageId = userMessageId;
        this.agentType = agentType;
        this.workingDir = workingDir;
        this.resumeId = resumeId;
        this.env = env;
        this.userId = userId;
        this.message = message;
        this.recallEnabled = recallEnabled;
        this.history = history == null
                ? Collections.<ChatRunHistoryMessageView>emptyList()
                : Collections.unmodifiableList(history);
        this.modeSnapshot = modeSnapshot;
        this.handoffFilePath = handoffFilePath;
    }

    /** 会话创建时冻结的模式快照；null = 默认模式。 */
    public com.example.agentweb.domain.mode.ModeSnapshot getModeSnapshot() {
        return modeSnapshot;
    }

    /** 本会话启动时加载的交接文件（工作目录相对路径）；null = 无交接。 */
    public String getHandoffFilePath() {
        return handoffFilePath;
    }
}
