package com.example.agentweb.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root representing a chat session bound to a working directory and an agent type.
 */
public class ChatSession {
    private final String id;
    private final AgentType agentType;
    private final String workingDir;
    private final Instant createdAt;
    private final List<ChatMessage> messages;

    public ChatSession(AgentType agentType, String workingDir) {
        this(UUID.randomUUID().toString(), agentType, workingDir, Instant.now(), new ArrayList<ChatMessage>());
    }

    @JsonCreator
    public ChatSession(@JsonProperty("id") String id,
                @JsonProperty("agentType") AgentType agentType,
                @JsonProperty("workingDir") String workingDir,
                @JsonProperty("createdAt") Instant createdAt,
                @JsonProperty("messages") List<ChatMessage> messages) {
        this.id = id;
        this.agentType = agentType;
        this.workingDir = workingDir;
        this.createdAt = createdAt;
        this.messages = messages != null ? new ArrayList<ChatMessage>(messages) : new ArrayList<ChatMessage>();
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

    public void addMessage(String role, String content) {
        messages.add(new ChatMessage(role, content));
    }

    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }
}
