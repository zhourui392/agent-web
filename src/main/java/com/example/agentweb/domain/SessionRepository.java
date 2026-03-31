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

    void deleteById(String id);
}
