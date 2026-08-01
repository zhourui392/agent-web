package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Workbench Run SSE 公共 envelope 的安全 JSON 组装器。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkbenchRunEventPayloadFactory {

    private static final String SCHEMA_VERSION =
            "workbench-run-event@1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OBJECT_MAP =
            new TypeReference<Map<String, Object>>() {
            };

    private WorkbenchRunEventPayloadFactory() {
    }

    static String status(
            WorkbenchRunSnapshot snapshot, ChatRunStatus status,
            Instant occurredAt) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("status", status.name());
        return envelope(snapshot, occurredAt, data);
    }

    static String project(
            WorkbenchRunSnapshot snapshot, ChatRunEvent event) {
        if (snapshot == null || event == null
                || !snapshot.getRunId().equals(
                event.getRunId().getValue())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        Map<String, Object> parsed = parseObject(event.getPayload());
        if (isWorkbenchEnvelope(parsed)) {
            requireExactEnvelope(snapshot, parsed);
            return envelope(
                    snapshot, event.getCreatedAt(), envelopeData(parsed));
        }
        Map<String, Object> data = parsed;
        if (data == null) {
            data = new LinkedHashMap<String, Object>();
            if ("agent_chunk".equals(event.getEventType())) {
                data.put("content", event.getPayload());
            } else {
                data.put("payload", event.getPayload());
            }
        }
        return envelope(snapshot, event.getCreatedAt(), data);
    }

    private static String envelope(
            WorkbenchRunSnapshot snapshot, Instant occurredAt,
            Map<String, Object> data) {
        if (snapshot == null || occurredAt == null || data == null) {
            throw new IllegalArgumentException(
                    "workbench run event envelope facts are required");
        }
        Map<String, Object> envelope =
                new LinkedHashMap<String, Object>();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("runId", snapshot.getRunId());
        envelope.put("workbenchId", snapshot.getWorkbenchId().getValue());
        envelope.put("phase", snapshot.getPhase().name());
        envelope.put("occurredAt", occurredAt.toEpochMilli());
        envelope.put("data", data);
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "workbench run status payload could not be serialized",
                    failure);
        }
    }

    private static Map<String, Object> parseObject(String payload) {
        try {
            return MAPPER.readValue(payload, OBJECT_MAP);
        } catch (JsonProcessingException failure) {
            return null;
        }
    }

    private static boolean isWorkbenchEnvelope(
            Map<String, Object> payload) {
        return payload != null
                && SCHEMA_VERSION.equals(payload.get("schemaVersion"));
    }

    private static void requireExactEnvelope(
            WorkbenchRunSnapshot snapshot,
            Map<String, Object> envelope) {
        if (!snapshot.getRunId().equals(envelope.get("runId"))
                || !snapshot.getWorkbenchId().getValue().equals(
                envelope.get("workbenchId"))
                || !snapshot.getPhase().name().equals(
                envelope.get("phase"))
                || !(envelope.get("data") instanceof Map)) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelopeData(
            Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("data");
    }
}
