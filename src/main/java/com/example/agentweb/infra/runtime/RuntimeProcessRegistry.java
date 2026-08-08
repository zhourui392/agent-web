package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.runtime.port.RuntimeState;
import com.example.agentweb.app.runtime.port.RuntimeTerminationReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * executionId、公共稳定 Handle 与真实 Process 的进程内注册表。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeProcessRegistry {

    private final ConcurrentMap<String, Entry> byExecutionId =
            new ConcurrentHashMap<String, Entry>();
    private final ConcurrentMap<String, Entry> byHandleId =
            new ConcurrentHashMap<String, Entry>();

    public RuntimeHandle register(String executionId, Process process) {
        return register(executionId, process, "runtime:" + UUID.randomUUID().toString());
    }

    private RuntimeHandle register(String executionId, Process process, String handleId) {
        if (executionId == null || executionId.trim().isEmpty() || process == null) {
            throw new IllegalArgumentException("runtime execution id and process are required");
        }
        RuntimeHandle handle = new RuntimeHandle(executionId, handleId);
        Entry entry = new Entry(handle, process);
        Entry previousExecution = byExecutionId.putIfAbsent(executionId, entry);
        if (previousExecution != null) {
            throw new IllegalStateException("runtime execution is already registered");
        }
        Entry previousHandle = byHandleId.putIfAbsent(handle.getHandleId(), entry);
        if (previousHandle != null) {
            byExecutionId.remove(executionId, entry);
            throw new IllegalStateException("runtime process handle is already registered");
        }
        return handle;
    }

    public Optional<Process> process(RuntimeHandle handle) {
        Entry entry = entry(handle);
        return entry == null ? Optional.<Process>empty() : entry.process();
    }

    public void addOutputBytes(RuntimeHandle handle, long bytes) {
        if (bytes < 0L) {
            throw new IllegalArgumentException("runtime output byte increment must not be negative");
        }
        Entry entry = requireEntry(handle);
        entry.addOutputBytes(bytes);
    }

    public void markStopRequested(RuntimeHandle handle) {
        Entry entry = entry(handle);
        if (entry != null) {
            entry.markStopRequested();
        }
    }

    public void markTerminated(RuntimeHandle handle, int exitCode,
                               RuntimeTerminationReason reason) {
        requireEntry(handle).markTerminated(exitCode, reason);
    }

    public RuntimeObservation observe(RuntimeHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("runtime handle is required");
        }
        Entry entry = entry(handle);
        return entry == null ? RuntimeObservation.notFound(handle) : entry.observe();
    }

    public void releaseProcess(RuntimeHandle handle) {
        Entry entry = entry(handle);
        if (entry != null) {
            entry.releaseProcess();
        }
    }

    /**
     * 迁移期 Adapter 用于彻底移除自身已持久化终态的技术注册项。
     */
    public void unregister(RuntimeHandle handle) {
        Entry entry = entry(handle);
        if (entry == null) {
            return;
        }
        byHandleId.remove(handle.getHandleId(), entry);
        byExecutionId.remove(handle.getExecutionId(), entry);
        entry.releaseProcess();
    }

    public List<RuntimeHandle> activeHandles() {
        List<RuntimeHandle> handles = new ArrayList<RuntimeHandle>();
        for (Entry entry : byExecutionId.values()) {
            if (entry.isActive()) {
                handles.add(entry.getHandle());
            }
        }
        return Collections.unmodifiableList(handles);
    }

    /** Returns the active handle for a compatibility caller keyed by execution id. */
    Optional<RuntimeHandle> activeHandle(String executionId) {
        if (executionId == null || executionId.trim().isEmpty()) {
            return Optional.empty();
        }
        Entry entry = byExecutionId.get(executionId);
        return entry != null && entry.isActive()
                ? Optional.of(entry.getHandle()) : Optional.<RuntimeHandle>empty();
    }

    private Entry entry(RuntimeHandle handle) {
        if (handle == null) {
            return null;
        }
        Entry entry = byHandleId.get(handle.getHandleId());
        return entry != null && entry.getHandle().equals(handle) ? entry : null;
    }

    private Entry requireEntry(RuntimeHandle handle) {
        Entry entry = entry(handle);
        if (entry == null) {
            throw new IllegalStateException("runtime handle is not registered");
        }
        return entry;
    }

    private static final class Entry {

        private final RuntimeHandle handle;
        private Process process;
        private RuntimeState state = RuntimeState.RUNNING;
        private long outputBytes;
        private Integer exitCode;
        private RuntimeTerminationReason terminationReason;

        private Entry(RuntimeHandle handle, Process process) {
            this.handle = handle;
            this.process = process;
        }

        private RuntimeHandle getHandle() {
            return handle;
        }

        private synchronized Optional<Process> process() {
            return Optional.ofNullable(process);
        }

        private synchronized void addOutputBytes(long value) {
            outputBytes += value;
        }

        private synchronized void markStopRequested() {
            if (state == RuntimeState.RUNNING) {
                state = RuntimeState.STOP_REQUESTED;
            }
        }

        private synchronized void markTerminated(int value,
                                                 RuntimeTerminationReason reason) {
            if (state == RuntimeState.TERMINATED) {
                return;
            }
            state = RuntimeState.TERMINATED;
            exitCode = Integer.valueOf(value);
            terminationReason = java.util.Objects.requireNonNull(reason, "reason");
        }

        private synchronized RuntimeObservation observe() {
            if (state == RuntimeState.RUNNING) {
                return RuntimeObservation.running(handle, outputBytes);
            }
            if (state == RuntimeState.STOP_REQUESTED) {
                return RuntimeObservation.stopRequested(handle, outputBytes);
            }
            return RuntimeObservation.terminated(handle, exitCode.intValue(),
                    terminationReason, outputBytes);
        }

        private synchronized void releaseProcess() {
            process = null;
        }

        private synchronized boolean isActive() {
            return process != null && state != RuntimeState.TERMINATED;
        }
    }
}
