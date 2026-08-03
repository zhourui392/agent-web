package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.runtime.port.RuntimeSemanticEvent;
import com.example.agentweb.app.runtime.port.RuntimeState;
import com.example.agentweb.app.runtime.port.RuntimeTermination;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 将已通过 Handle fencing 的公共 Runtime 事件投影为可恢复 ChatRun 事件并收口终态。
 *
 * @author alex
 * @since 2026-08-01
 */
final class ChatRunRuntimeEventProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatRunId runId;
    private final ChatRunLifecycleService lifecycleService;
    private final AgentExecutionGateway executionGateway;
    private final ChatRunRuntimeTerminationReconciler terminationReconciler;

    ChatRunRuntimeEventProcessor(ChatRunId runId,
                                 ChatRunLifecycleService lifecycleService,
                                 AgentExecutionGateway executionGateway,
                                 ChatRunRuntimeTerminationReconciler
                                         terminationReconciler) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.lifecycleService = Objects.requireNonNull(
                lifecycleService, "lifecycleService");
        this.executionGateway = Objects.requireNonNull(
                executionGateway, "executionGateway");
        this.terminationReconciler = Objects.requireNonNull(
                terminationReconciler, "terminationReconciler");
    }

    void process(RuntimeHandle handle, RuntimeEvent event) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(event, "event");
        switch (event.getType()) {
            case STARTED:
                lifecycleService.start(runId);
                appendRuntimeEvent("runtime_started", event);
                return;
            case OUTPUT:
                appendSemanticEvents(event);
                return;
            case DIAGNOSTIC:
                appendRuntimeEvent("runtime_diagnostic", event);
                return;
            case STOP_REQUESTED:
                appendRuntimeEvent("runtime_stop_requested", event);
                return;
            case TERMINATED:
                appendRuntimeEvent("runtime_terminated", event);
                finalizeTermination(handle);
                return;
            default:
                throw new IllegalArgumentException(
                        "unsupported runtime event type: " + event.getType());
        }
    }

    private void appendRuntimeEvent(String eventType, RuntimeEvent event) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("runtimeSequence", Long.valueOf(event.getSequence()));
        payload.put("runtimeType", event.getType().name());
        payload.put("payload", event.getSafePayload());
        lifecycleService.append(runId, eventType, serialize(payload));
    }

    private void appendSemanticEvents(RuntimeEvent event) {
        for (RuntimeSemanticEvent semantic : event.getSemanticEvents()) {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("runtimeSequence", Long.valueOf(event.getSequence()));
            payload.putAll(semantic.getData());
            lifecycleService.append(runId, semantic.getEventType(),
                    serialize(payload));
        }
    }

    private void finalizeTermination(RuntimeHandle handle) {
        RuntimeObservation observation = executionGateway.observe(handle);
        if (observation.getState() != RuntimeState.TERMINATED
                || !observation.termination().isPresent()) {
            lifecycleService.fail(runId, "RUNTIME_TERMINATION_UNAVAILABLE",
                    "Runtime 终态不可确认，任务已停止", null);
            return;
        }
        RuntimeTermination termination = observation.termination().get();
        terminationReconciler.reconcile(runId, handle, termination);
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not serialize runtime event", ex);
        }
    }
}
