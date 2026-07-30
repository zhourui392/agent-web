package com.example.agentweb.infra.nativeagent;

import com.anthropic.agentkit.domain.diagnosis.DataSourceType;
import com.anthropic.agentkit.domain.diagnosis.DataSourceView;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentContext;
import com.anthropic.agentkit.domain.diagnosis.OperationalContext;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.anthropic.agentkit.interfaces.engine.DiagnoseEngine;
import com.anthropic.agentkit.interfaces.engine.DiagnosisCapability;
import com.anthropic.agentkit.interfaces.engine.DiagnosisReadiness;
import com.example.agentweb.config.nativeagent.NativeDiagnosisProperties;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One immutable environment-to-engine binding with no credential projection.
 *
 * @author alex
 * @since 2026-07-30
 */
public final class NativeDiagnosisEnvironmentBinding {

    private final DiagnoseEngine engine;
    private final String environment;
    private final long timeoutSeconds;
    private final String defaultService;
    private final String dataSourceId;
    private final ZoneId zoneId;
    private final boolean logSourceConfigured;
    private final Clock clock;

    private NativeDiagnosisEnvironmentBinding(DiagnoseEngine engine, String environment,
                                              NativeDiagnosisProperties properties,
                                              Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.environment = requireText(environment, "environment");
        NativeDiagnosisProperties configuration = Objects.requireNonNull(properties, "properties");
        this.timeoutSeconds = configuration.getTimeoutSeconds();
        LogSource source = logSource(configuration);
        this.defaultService = source.service();
        this.dataSourceId = source.dataSourceId();
        this.zoneId = configuration.getLocalLogs().isEnabled()
                ? configuration.getLocalLogs().zoneId() : configuration.zoneId();
        this.logSourceConfigured = source.configured();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static NativeDiagnosisEnvironmentBinding from(
            DiagnoseEngine engine, String environment,
            NativeDiagnosisProperties properties, Clock clock) {
        return new NativeDiagnosisEnvironmentBinding(engine, environment, properties, clock);
    }

    public DiagnoseEngine engine() {
        return engine;
    }

    public String environment() {
        return environment;
    }

    public long effectiveTimeout(long requestedSeconds) {
        return requestedSeconds <= 0L
                ? timeoutSeconds : Math.min(requestedSeconds, timeoutSeconds);
    }

    public OperationalContext operationalContext() {
        return new OperationalContext(
                clock.instant(), zoneId, EnvironmentContext.named(environment),
                defaultService, dataSources(), Map.of());
    }

    private List<DataSourceView> dataSources() {
        if (!logSourceConfigured) {
            return List.of();
        }
        CapabilityView capability = logCapability();
        return List.of(new DataSourceView(
                dataSourceId, DataSourceType.LOG,
                capability.readiness(), capability.operations()));
    }

    private CapabilityView logCapability() {
        DiagnosisReadiness readiness = engine.readiness();
        if (readiness == null) {
            return new CapabilityView(ReadinessStatus.READY, Set.of("query"));
        }
        return readiness.capabilities().stream()
                .filter(this::isBoundLogCapability)
                .findFirst()
                .map(capability -> new CapabilityView(
                        capability.readiness(), capability.operations()))
                .orElseGet(() -> new CapabilityView(
                        readiness.status(), Set.of("query")));
    }

    private boolean isBoundLogCapability(DiagnosisCapability capability) {
        return "LogQuery".equals(capability.toolName())
                && (capability.dataSourceId().isBlank()
                || dataSourceId.equals(capability.dataSourceId()));
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }

    private static LogSource logSource(NativeDiagnosisProperties properties) {
        if (properties.getLocalLogs().isEnabled()) {
            return new LogSource(
                    requireText(properties.getLocalLogs().getService(), "defaultService"),
                    requireText(properties.getLocalLogs().getDataSourceId(), "dataSourceId"), true);
        }
        if (properties.getBackends().getLogQueryUrl() != null
                && !properties.getBackends().getLogQueryUrl().isBlank()) {
            return new LogSource(
                    requireText(properties.getBackends().getLogQueryService(), "defaultService"),
                    requireText(properties.getBackends().getLogQueryDataSourceId(), "dataSourceId"), true);
        }
        return new LogSource("", "", false);
    }

    private record LogSource(String service, String dataSourceId, boolean configured) {
    }

    private record CapabilityView(ReadinessStatus readiness, Set<String> operations) {
    }
}
