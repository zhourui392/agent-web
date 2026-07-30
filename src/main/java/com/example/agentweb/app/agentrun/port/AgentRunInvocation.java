package com.example.agentweb.app.agentrun.port;

import com.example.agentweb.domain.shared.AgentType;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete provider-neutral input for one agent execution.
 *
 * @author alex
 * @since 2026-07-29
 */
@Getter
@Builder
public final class AgentRunInvocation {

    private final String runId;
    private final String conversationId;
    private final long userMessageId;
    private final AgentType agentType;
    private final String workingDir;
    private final String prompt;
    private final String resumeId;
    private final String env;
    private final String userId;
    private final long timeoutSeconds;
    private final List<AgentHistoryMessage> history;
    private final Map<String, String> extraEnv;

    private AgentRunInvocation(String runId, String conversationId, long userMessageId,
                               AgentType agentType, String workingDir, String prompt,
                               String resumeId, String env, String userId, long timeoutSeconds,
                               List<AgentHistoryMessage> history, Map<String, String> extraEnv) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.userMessageId = userMessageId;
        this.agentType = Objects.requireNonNull(agentType, "agentType");
        this.workingDir = Objects.requireNonNull(workingDir, "workingDir");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.resumeId = resumeId;
        this.env = env;
        this.userId = userId;
        this.timeoutSeconds = timeoutSeconds;
        this.history = history == null ? Collections.emptyList() : List.copyOf(history);
        this.extraEnv = extraEnv == null ? Collections.emptyMap() : Map.copyOf(extraEnv);
    }
}
