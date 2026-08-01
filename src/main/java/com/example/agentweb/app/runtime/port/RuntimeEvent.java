package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 带单调序号的 Provider 中立、非敏感 Runtime 事件。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeEvent {

    public static final int MAX_SAFE_PAYLOAD_LENGTH = 65_536;

    private final String executionId;
    private final long sequence;
    private final RuntimeEventType type;
    private final String safePayload;
    private final String normalizedAssistantText;
    private final List<RuntimeSemanticEvent> semanticEvents;

    public RuntimeEvent(String executionId, long sequence,
                        RuntimeEventType type, String safePayload) {
        this(executionId, sequence, type, safePayload, null);
    }

    public RuntimeEvent(String executionId, long sequence,
                        RuntimeEventType type, String safePayload,
                        String normalizedAssistantText) {
        this(executionId, sequence, type, safePayload,
                normalizedAssistantText,
                Collections.<RuntimeSemanticEvent>emptyList());
    }

    public RuntimeEvent(String executionId, long sequence,
                        RuntimeEventType type, String safePayload,
                        String normalizedAssistantText,
                        List<RuntimeSemanticEvent> semanticEvents) {
        this.executionId = DomainText.require(executionId, "runtime event execution id", 160);
        if (sequence < 1L) {
            throw new IllegalArgumentException("runtime event sequence must be positive");
        }
        if (safePayload == null) {
            throw new IllegalArgumentException("runtime event safe payload must not be null");
        }
        if (safePayload.length() > MAX_SAFE_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("runtime event safe payload exceeds limit");
        }
        this.sequence = sequence;
        this.type = Objects.requireNonNull(type, "type");
        this.safePayload = safePayload;
        if (normalizedAssistantText != null
                && (type != RuntimeEventType.OUTPUT
                || normalizedAssistantText.trim().isEmpty()
                || normalizedAssistantText.length()
                > MAX_SAFE_PAYLOAD_LENGTH)) {
            throw new IllegalArgumentException(
                    "normalized assistant text must be bounded non-blank Runtime output");
        }
        this.normalizedAssistantText = normalizedAssistantText;
        if (semanticEvents == null || semanticEvents.contains(null)) {
            throw new IllegalArgumentException(
                    "runtime semantic events must be complete");
        }
        if (type != RuntimeEventType.OUTPUT
                && (!semanticEvents.isEmpty()
                || normalizedAssistantText != null)) {
            throw new IllegalArgumentException(
                    "runtime semantics are only valid for Runtime output");
        }
        List<RuntimeSemanticEvent> copy =
                new ArrayList<RuntimeSemanticEvent>(semanticEvents);
        if (normalizedAssistantText != null
                && !containsAgentChunk(copy)) {
            copy.add(RuntimeSemanticEvent.agentChunk(
                    normalizedAssistantText));
        }
        this.semanticEvents = Collections.unmodifiableList(copy);
    }

    public Optional<String> assistantText() {
        return Optional.ofNullable(normalizedAssistantText);
    }

    private boolean containsAgentChunk(
            List<RuntimeSemanticEvent> events) {
        for (RuntimeSemanticEvent event : events) {
            if ("agent_chunk".equals(event.getEventType())) {
                return true;
            }
        }
        return false;
    }
}
