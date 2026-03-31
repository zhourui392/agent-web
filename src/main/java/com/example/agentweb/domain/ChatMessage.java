package com.example.agentweb.domain;

import java.time.Instant;

/**
 * Value object representing a single message in a chat session.
 */
public class ChatMessage {
    private String role;
    private String content;
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

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getTimestamp() {
        return timestamp;
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
