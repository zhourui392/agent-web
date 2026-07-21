package com.example.agentweb.domain.chat;

import lombok.Getter;
import java.time.Instant;

/**
 * Value object representing a single message in a chat session.
 * @author zhourui(V33215020)
 */
public class ChatMessage {
    @Getter
    private Long id;
    @Getter
    private String role;
    @Getter
    private String content;
    @Getter
    private Instant timestamp;

    /**
     * No-arg constructor for Jackson deserialization
     */
    private ChatMessage() {
    }

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
        this.timestamp = Instant.now();
    }

    public ChatMessage(String role, String content, Instant timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public ChatMessage(Long id, String role, String content, Instant timestamp) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    private void setId(Long id) {
        this.id = id;
    }

    private void setRole(String role) {
        this.role = role;
    }

    private void setContent(String content) {
        this.content = content;
    }

    private void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
