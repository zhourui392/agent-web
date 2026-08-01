package com.example.agentweb.infra.workbench.metrics;

import com.example.agentweb.app.workbench.document.DocumentKind;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.workbench.HighImpactOperationType;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Workbench 发布指标的 Micrometer 适配器。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public final class MicrometerWorkbenchTelemetry
        implements WorkbenchTelemetry {

    private static final String UNKNOWN = "UNKNOWN";
    private static final Pattern SAFE_TAG =
            Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final MeterRegistry registry;

    public MicrometerWorkbenchTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void workbenchCreated(String result) {
        counter("workbench.creation", "result", tag(result)).increment();
    }

    @Override
    public void runTerminal(
            WorkbenchPhase phase, RunMode mode,
            String status, Duration duration) {
        String phaseTag = enumTag(phase);
        String modeTag = enumTag(mode);
        counter("workbench.run",
                "phase", phaseTag,
                "mode", modeTag,
                "status", tag(status)).increment();
        Timer.builder("workbench.run.duration")
                .tags("phase", phaseTag, "mode", modeTag)
                .register(registry)
                .record(nonNegative(duration).toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void writeConflict() {
        counter("workbench.write.conflict").increment();
    }

    @Override
    public void sseReconnect(String result) {
        counter("workbench.sse.reconnect",
                "result", tag(result)).increment();
    }

    @Override
    public void eventLag(Duration lag) {
        DistributionSummary.builder("workbench.event.lag")
                .baseUnit("seconds")
                .register(registry)
                .record(nonNegative(lag).toNanos() / 1_000_000_000.0D);
    }

    @Override
    public void capabilityResolution(String result) {
        counter("workbench.capability.resolution",
                "result", tag(result)).increment();
    }

    @Override
    public void capabilityVersionChanged() {
        counter("workbench.capability.version.change").increment();
    }

    @Override
    public void workspaceScopeViolation() {
        counter("workbench.workspace.scope.violation").increment();
    }

    @Override
    public void documentRead(DocumentKind kind, String result) {
        counter("workbench.document.read",
                "kind", enumTag(kind),
                "result", tag(result)).increment();
    }

    @Override
    public void handoffConflict() {
        counter("workbench.handoff.conflict").increment();
    }

    @Override
    public void operation(
            HighImpactOperationType type, String status) {
        counter("workbench.operation",
                "type", enumTag(type),
                "status", tag(status)).increment();
    }

    @Override
    public void recoveryReconciliation(String result) {
        counter("workbench.recovery.reconciliation",
                "result", tag(result)).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private String enumTag(Enum<?> value) {
        return value == null ? UNKNOWN : tag(value.name());
    }

    private String tag(String value) {
        return value != null && SAFE_TAG.matcher(value).matches()
                ? value : UNKNOWN;
    }

    private Duration nonNegative(Duration value) {
        if (value == null || value.isNegative()) {
            return Duration.ZERO;
        }
        return value;
    }
}
