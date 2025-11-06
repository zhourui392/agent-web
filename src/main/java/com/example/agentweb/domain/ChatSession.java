package com.example.agentweb.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root representing a chat session bound to a working directory and an agent type.
 */
public class ChatSession {
    private final String id;
    private final AgentType agentType;
    private final String workingDir;
    private final Instant createdAt;

    public ChatSession(AgentType agentType, String workingDir) {
        this.id = UUID.randomUUID().toString();
        this.agentType = agentType;
        this.workingDir = workingDir;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
