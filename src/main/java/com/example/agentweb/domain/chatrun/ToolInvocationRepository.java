package com.example.agentweb.domain.chatrun;

import java.util.List;

public interface ToolInvocationRepository {

    void save(ToolInvocation invocation);

    void attachAssistantMessage(String runId, long assistantMessageId);

    void completeExplicitSkills(String runId, ToolInvocationStatus status);

    List<ToolInvocation> findBySessionId(String sessionId, int limit, int offset);

    List<ToolInvocation> findByRunId(String runId, int limit, int offset);
}
