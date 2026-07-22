package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Generates unpredictable run identifiers.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class ChatRunIdGenerator {

    public ChatRunId nextId() {
        return ChatRunId.of(UUID.randomUUID().toString());
    }
}
