package com.example.agentweb.domain;

import java.util.List;

/**
 * Port for persisting chat sessions and their messages.
 */
public interface SessionRepository {

    void saveSession(ChatSession session);

    void addMessage(String sessionId, ChatMessage message);

    ChatSession findById(String id);

    List<ChatSession> findAll();
}
