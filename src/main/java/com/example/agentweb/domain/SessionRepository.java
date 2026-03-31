package com.example.agentweb.domain;

import java.util.List;
import java.util.Map;

/**
 * Port for persisting chat sessions and their messages.
 */
public interface SessionRepository {

    void saveSession(ChatSession session);

    void addMessage(String sessionId, ChatMessage message);

    ChatSession findById(String id);

    List<ChatSession> findAll();

    /**
     * Returns session summaries including title (first user message).
     * Each map contains: sessionId, agentType, workingDir, createdAt, messageCount, title.
     */
    List<Map<String, Object>> findAllSummary();

    /**
     * Paged version of findAllSummary.
     * @param offset number of rows to skip
     * @param limit  max rows to return
     */
    List<Map<String, Object>> findSummaryPaged(int offset, int limit);

    void deleteById(String id);
}
