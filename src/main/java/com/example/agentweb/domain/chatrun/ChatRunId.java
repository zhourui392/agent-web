package com.example.agentweb.domain.chatrun;

import java.util.Objects;

/**
 * Chat run identifier.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public final class ChatRunId {

    private final String value;

    private ChatRunId(String value) {
        this.value = value;
    }

    public static ChatRunId of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("chat run id must not be blank");
        }
        return new ChatRunId(value.trim());
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChatRunId)) {
            return false;
        }
        ChatRunId that = (ChatRunId) other;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
