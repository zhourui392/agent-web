package com.example.agentweb.app.runtime.port;

import lombok.Getter;

import java.util.Objects;
import java.util.Optional;

/**
 * 对一个 Runtime Handle 的 Provider 中立技术观察。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeObservation {

    private final RuntimeHandle handle;
    private final RuntimeState state;
    private final long observedOutputBytes;
    private final RuntimeTermination terminalFact;

    private RuntimeObservation(RuntimeHandle handle, RuntimeState state,
                               long observedOutputBytes, RuntimeTermination terminalFact) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.state = Objects.requireNonNull(state, "state");
        if (observedOutputBytes < 0L) {
            throw new IllegalArgumentException("observed output bytes must not be negative");
        }
        if (state == RuntimeState.TERMINATED && terminalFact == null) {
            throw new IllegalArgumentException("terminated observation requires terminal fact");
        }
        if (state != RuntimeState.TERMINATED && terminalFact != null) {
            throw new IllegalArgumentException(
                    "non-terminal observation must not contain terminal fact");
        }
        this.observedOutputBytes = observedOutputBytes;
        this.terminalFact = terminalFact;
    }

    public static RuntimeObservation running(RuntimeHandle handle, long observedOutputBytes) {
        return new RuntimeObservation(
                handle, RuntimeState.RUNNING, observedOutputBytes, null);
    }

    public static RuntimeObservation stopRequested(
            RuntimeHandle handle, long observedOutputBytes) {
        return new RuntimeObservation(
                handle, RuntimeState.STOP_REQUESTED, observedOutputBytes, null);
    }

    public static RuntimeObservation terminated(
            RuntimeHandle handle, int exitCode, RuntimeTerminationReason reason,
            long observedOutputBytes) {
        return new RuntimeObservation(handle, RuntimeState.TERMINATED, observedOutputBytes,
                new RuntimeTermination(exitCode, reason));
    }

    public static RuntimeObservation notFound(RuntimeHandle handle) {
        return new RuntimeObservation(handle, RuntimeState.NOT_FOUND, 0L, null);
    }

    public Optional<RuntimeTermination> termination() {
        return Optional.ofNullable(terminalFact);
    }
}
