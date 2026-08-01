package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeSemanticEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 单个 Runtime Execution 内依据已观察工具生命周期计算单调耗时。
 *
 * @author alex
 * @since 2026-08-01
 */
final class RuntimeToolTimingTracker {

    private static final String TOOL_STARTED = "tool_started";
    private static final String TOOL_FINISHED = "tool_finished";

    private final String executionId;
    private final LongSupplier nanoTimeSource;
    private final Map<String, Long> startedNanos =
            new HashMap<String, Long>();
    private final Set<String> terminalCallIds =
            new HashSet<String>();

    RuntimeToolTimingTracker(
            String executionId, LongSupplier nanoTimeSource) {
        if (executionId == null || executionId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "runtime tool timing execution id must not be blank");
        }
        this.executionId = executionId;
        this.nanoTimeSource = Objects.requireNonNull(
                nanoTimeSource, "nanoTimeSource");
    }

    synchronized RuntimeEvent enhance(RuntimeEvent event) {
        Objects.requireNonNull(event, "event");
        if (!executionId.equals(event.getExecutionId())
                || event.getSemanticEvents().isEmpty()) {
            return event;
        }
        boolean changed = false;
        List<RuntimeSemanticEvent> enhanced =
                new ArrayList<RuntimeSemanticEvent>(
                        event.getSemanticEvents().size());
        for (RuntimeSemanticEvent semantic : event.getSemanticEvents()) {
            RuntimeSemanticEvent observed = observe(semantic);
            enhanced.add(observed);
            changed = changed || observed != semantic;
        }
        if (!changed) {
            return event;
        }
        return new RuntimeEvent(
                event.getExecutionId(), event.getSequence(), event.getType(),
                event.getSafePayload(), event.getNormalizedAssistantText(),
                enhanced);
    }

    synchronized void clear() {
        startedNanos.clear();
        terminalCallIds.clear();
    }

    private RuntimeSemanticEvent observe(RuntimeSemanticEvent semantic) {
        String eventType = semantic.getEventType();
        Object callIdValue = semantic.getData().get("callId");
        if (!(callIdValue instanceof String)) {
            return semantic;
        }
        String callId = (String) callIdValue;
        if (TOOL_STARTED.equals(eventType)) {
            if (terminalCallIds.contains(callId)) {
                return semantic;
            }
            if (startedNanos.remove(callId) != null) {
                terminalCallIds.add(callId);
                return semantic;
            }
            startedNanos.put(
                    callId, Long.valueOf(nanoTimeSource.getAsLong()));
            return semantic;
        }
        if (!TOOL_FINISHED.equals(eventType)) {
            return semantic;
        }
        if (!terminalCallIds.add(callId)) {
            return semantic;
        }
        Long started = startedNanos.remove(callId);
        if (started == null) {
            return semantic;
        }
        long elapsedNanos = nanoTimeSource.getAsLong()
                - started.longValue();
        if (elapsedNanos < 0L) {
            return semantic;
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        if (durationMs > RuntimeSemanticEvent.MAX_TOOL_DURATION_MILLIS) {
            return semantic;
        }
        return semantic.withDurationMs(durationMs);
    }
}
