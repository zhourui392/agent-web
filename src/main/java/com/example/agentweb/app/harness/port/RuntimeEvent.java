package com.example.agentweb.app.harness.port;

import com.example.agentweb.domain.harness.RuntimeExecutionSignal;
import com.example.agentweb.domain.harness.RuntimeArtifactBundle;
import com.example.agentweb.domain.harness.RuntimeCommandObservation;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runtime Adapter 到应用回调入口的归一化非敏感事件。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class RuntimeEvent {

    private final String executionId;
    private final RuntimeExecutionSignal signal;
    private final String summary;
    private final RuntimeArtifactBundle artifactBundle;
    private final List<RuntimeCommandObservation> commandObservations;

    public RuntimeEvent(String executionId, RuntimeExecutionSignal signal, String summary) {
        this(executionId, signal, summary, null,
                Collections.<RuntimeCommandObservation>emptyList());
    }

    public RuntimeEvent(String executionId, RuntimeExecutionSignal signal, String summary,
                        RuntimeArtifactBundle artifactBundle) {
        this(executionId, signal, summary, artifactBundle,
                Collections.<RuntimeCommandObservation>emptyList());
    }

    public RuntimeEvent(String executionId, RuntimeExecutionSignal signal, String summary,
                        RuntimeArtifactBundle artifactBundle,
                        List<RuntimeCommandObservation> commandObservations) {
        if (executionId == null || executionId.trim().isEmpty() || signal == null) {
            throw new IllegalArgumentException("runtime event identity and signal are required");
        }
        if (commandObservations == null || commandObservations.contains(null)) {
            throw new IllegalArgumentException("runtime command observations must not be null");
        }
        this.executionId = executionId.trim();
        this.signal = signal;
        this.summary = summary;
        this.artifactBundle = artifactBundle;
        this.commandObservations = Collections.unmodifiableList(
                new ArrayList<RuntimeCommandObservation>(commandObservations));
    }
}
