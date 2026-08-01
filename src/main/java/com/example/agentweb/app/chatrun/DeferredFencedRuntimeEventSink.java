package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.domain.chatrun.ChatRunId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 在 RuntimeHandle 持久化前延迟回调，并按当前持久化 Handle 隔离旧执行事件。
 *
 * @author alex
 * @since 2026-08-01
 */
final class DeferredFencedRuntimeEventSink implements RuntimeEventSink {

    private final ChatRunId runId;
    private final ChatRunRuntimeHandleStore handleStore;
    private final ChatRunRuntimeEventProcessor processor;
    private final List<RuntimeEvent> pendingEvents = new ArrayList<RuntimeEvent>();

    private RuntimeHandle expectedHandle;
    private State state = State.DEFERRED;

    DeferredFencedRuntimeEventSink(ChatRunId runId,
                                   ChatRunRuntimeHandleStore handleStore,
                                   ChatRunRuntimeEventProcessor processor) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.handleStore = Objects.requireNonNull(handleStore, "handleStore");
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    @Override
    public synchronized void onEvent(RuntimeEvent event) {
        RuntimeEvent currentEvent = Objects.requireNonNull(event, "event");
        if (state == State.REJECTED) {
            return;
        }
        if (state == State.DEFERRED) {
            pendingEvents.add(currentEvent);
            return;
        }
        deliverIfCurrent(currentEvent);
    }

    synchronized void activate(RuntimeHandle handle) {
        if (state != State.DEFERRED) {
            throw new IllegalStateException("runtime event sink is not deferred");
        }
        expectedHandle = Objects.requireNonNull(handle, "handle");
        state = State.ACTIVE;
        List<RuntimeEvent> buffered = new ArrayList<RuntimeEvent>(pendingEvents);
        pendingEvents.clear();
        for (RuntimeEvent event : buffered) {
            deliverIfCurrent(event);
        }
    }

    synchronized void reject() {
        state = State.REJECTED;
        expectedHandle = null;
        pendingEvents.clear();
    }

    private void deliverIfCurrent(RuntimeEvent event) {
        if (!runId.getValue().equals(event.getExecutionId())) {
            return;
        }
        Optional<RuntimeHandle> current = handleStore.find(runId);
        if (!current.isPresent() || !current.get().equals(expectedHandle)) {
            return;
        }
        processor.process(expectedHandle, event);
    }

    private enum State {
        DEFERRED,
        ACTIVE,
        REJECTED
    }
}
