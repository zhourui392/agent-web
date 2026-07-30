package com.example.agentweb.infra.nativeagent;

import com.anthropic.agentkit.interfaces.engine.DiagnoseEngine;
import com.anthropic.agentkit.interfaces.engine.RunRequest;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentRunInvocation;
import com.example.agentweb.app.agentrun.port.AgentRuntime;
import com.example.agentweb.app.agentrun.port.HistoryDeliveryMode;
import com.example.agentweb.app.agentrun.NativeDiagnosisReadinessQueryService;
import com.example.agentweb.app.agentrun.NativeDiagnosisReadinessView;
import com.example.agentweb.config.nativeagent.NativeDiagnosisProperties;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpointRepository;
import com.example.agentweb.domain.shared.AgentType;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * In-process runtime adapter for the AgentKit diagnosis engine.
 *
 * @author alex
 * @since 2026-07-29
 */
public final class NativeDiagnosisAgentRuntime
        implements AgentRuntime, NativeDiagnosisReadinessQueryService, AutoCloseable {

    private final Map<String, NativeDiagnosisEnvironmentBinding> environments;
    private final Map<String, DiagnoseEngine> activeEngines = new ConcurrentHashMap<>();
    private final DiagnosisCheckpointRepository checkpoints;
    private final NativeDiagnosisHistoryMapper historyMapper;
    private final NativeRunSummaryMapper summaryMapper;
    private final NativeDiagnosisTelemetry telemetry;

    public NativeDiagnosisAgentRuntime(DiagnoseEngine engine,
                                       NativeDiagnosisProperties properties,
                                       DiagnosisCheckpointRepository checkpoints,
                                       NativeDiagnosisHistoryMapper historyMapper,
                                       NativeRunSummaryMapper summaryMapper) {
        this(engine, properties, checkpoints, historyMapper, summaryMapper,
                Clock.systemUTC(), null);
    }

    public NativeDiagnosisAgentRuntime(DiagnoseEngine engine,
                                       NativeDiagnosisProperties properties,
                                       DiagnosisCheckpointRepository checkpoints,
                                       NativeDiagnosisHistoryMapper historyMapper,
                                       NativeRunSummaryMapper summaryMapper,
                                       Clock clock) {
        this(engine, properties, checkpoints, historyMapper, summaryMapper, clock, null);
    }

    NativeDiagnosisAgentRuntime(DiagnoseEngine engine,
                                NativeDiagnosisProperties properties,
                                DiagnosisCheckpointRepository checkpoints,
                                NativeDiagnosisHistoryMapper historyMapper,
                                NativeRunSummaryMapper summaryMapper,
                                Clock clock,
                                NativeDiagnosisTelemetry telemetry) {
        NativeDiagnosisProperties configuration = Objects.requireNonNull(
                properties, "properties");
        String environment = configuration.getBoundEnvironment().trim();
        this.environments = Map.of(environment, NativeDiagnosisEnvironmentBinding.from(
                Objects.requireNonNull(engine, "engine"), environment, configuration, clock));
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.historyMapper = Objects.requireNonNull(historyMapper, "historyMapper");
        this.summaryMapper = Objects.requireNonNull(summaryMapper, "summaryMapper");
        this.telemetry = telemetry;
        recordReadiness();
    }

    public NativeDiagnosisAgentRuntime(
            Map<String, NativeDiagnosisEnvironmentBinding> environments,
            DiagnosisCheckpointRepository checkpoints,
            NativeDiagnosisHistoryMapper historyMapper,
            NativeRunSummaryMapper summaryMapper) {
        this(environments, checkpoints, historyMapper, summaryMapper, null);
    }

    public NativeDiagnosisAgentRuntime(
            Map<String, NativeDiagnosisEnvironmentBinding> environments,
            DiagnosisCheckpointRepository checkpoints,
            NativeDiagnosisHistoryMapper historyMapper,
            NativeRunSummaryMapper summaryMapper,
            NativeDiagnosisTelemetry telemetry) {
        this.environments = checkedBindings(environments);
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.historyMapper = Objects.requireNonNull(historyMapper, "historyMapper");
        this.summaryMapper = Objects.requireNonNull(summaryMapper, "summaryMapper");
        this.telemetry = telemetry;
        recordReadiness();
    }

    @Override
    public Set<AgentType> supportedTypes() {
        return Collections.singleton(AgentType.NATIVE);
    }

    @Override
    public HistoryDeliveryMode historyDeliveryMode() {
        return HistoryDeliveryMode.TYPED;
    }

    @Override
    public void run(AgentRunInvocation invocation, Consumer<String> onChunk,
                    Consumer<AgentExecutionResult> onComplete) {
        NativeDiagnosisEnvironmentBinding binding = requireEnvironment(invocation.getEnv());
        String stateSnapshot = checkpoints.findLatestValidBefore(
                        invocation.getConversationId(), invocation.getUserMessageId())
                .map(checkpoint -> checkpoint.getStateSnapshot())
                .orElse("");
        RunRequest request = RunRequest.builder()
                .workingDir(invocation.getWorkingDir())
                .userMessage(invocation.getPrompt())
                .sessionId(invocation.getRunId())
                .operationalContext(binding.operationalContext())
                .timeoutSeconds(binding.effectiveTimeout(invocation.getTimeoutSeconds()))
                .history(historyMapper.map(invocation.getHistory()))
                .stateSnapshot(stateSnapshot)
                .build();
        register(invocation.getRunId(), binding.engine());
        NativeDiagnosisTelemetry.RunObservation observation = telemetry == null ? null
                : telemetry.start(binding.environment(), invocation.getRunId(),
                invocation.getConversationId(), stateSnapshot);
        AtomicBoolean terminalObserved = new AtomicBoolean();
        try {
            binding.engine().run(request, onChunk, summary -> {
                activeEngines.remove(invocation.getRunId(), binding.engine());
                if (telemetry != null && terminalObserved.compareAndSet(false, true)) {
                    telemetry.complete(observation, summary);
                }
                onComplete.accept(summaryMapper.map(summary));
            });
        } catch (RuntimeException failure) {
            activeEngines.remove(invocation.getRunId(), binding.engine());
            if (telemetry != null && terminalObserved.compareAndSet(false, true)) {
                telemetry.failed(observation, failure);
            }
            throw failure;
        }
    }

    @Override
    public void stop(String runId) {
        DiagnoseEngine active = activeEngines.get(runId);
        if (active != null) {
            active.stop(runId);
        } else if (environments.size() == 1) {
            environments.values().iterator().next().engine().stop(runId);
        }
    }

    @Override
    public String extractResumeId(AgentType type, String rawLine) {
        return null;
    }

    @Override
    public List<String> normalizeChunk(AgentType type, String rawLine) {
        return Collections.singletonList(rawLine);
    }

    public Set<String> boundEnvironments() {
        return environments.keySet();
    }

    public Set<String> operationalEnvironments() {
        return environments.entrySet().stream()
                .filter(entry -> entry.getValue().engine().readiness().status()
                        != ReadinessStatus.UNAVAILABLE)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public List<NativeDiagnosisReadinessView> currentReadiness() {
        List<NativeDiagnosisReadinessView> views = environments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> readiness(entry.getKey(), entry.getValue().engine()))
                .toList();
        recordReadiness();
        return views;
    }

    private NativeDiagnosisReadinessView readiness(String environment, DiagnoseEngine engine) {
        var readiness = engine.readiness();
        List<NativeDiagnosisReadinessView.Capability> capabilities =
                readiness.capabilities().stream().map(capability ->
                        new NativeDiagnosisReadinessView.Capability(
                                capability.toolName(), capability.dataSourceId(),
                                capability.environment(), capability.readiness().name(),
                                capability.operations(), capability.reasonCode())).toList();
        return new NativeDiagnosisReadinessView(
                environment, "CONFIGURED", readiness.mode().name(),
                readiness.status().name(), readiness.reasonCode(), capabilities);
    }

    @Override
    public void close() {
        environments.values().stream().map(NativeDiagnosisEnvironmentBinding::engine)
                .distinct().forEach(DiagnoseEngine::close);
    }

    private NativeDiagnosisEnvironmentBinding requireEnvironment(String requested) {
        if (requested == null || requested.isBlank()) {
            if (environments.size() == 1) {
                return environments.values().iterator().next();
            }
            throw new IllegalArgumentException(
                    "NATIVE runtime requires an explicit bound environment");
        }
        NativeDiagnosisEnvironmentBinding binding = environments.get(requested.trim());
        if (binding == null) {
            throw new IllegalArgumentException(
                    "NATIVE runtime is not bound to environment: " + requested.trim());
        }
        return binding;
    }

    private void register(String runId, DiagnoseEngine engine) {
        if (activeEngines.putIfAbsent(runId, engine) != null) {
            throw new IllegalStateException("NATIVE run already active: " + runId);
        }
    }

    private static Map<String, NativeDiagnosisEnvironmentBinding> checkedBindings(
            Map<String, NativeDiagnosisEnvironmentBinding> values) {
        Objects.requireNonNull(values, "environments");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("at least one NATIVE environment is required");
        }
        Map<String, NativeDiagnosisEnvironmentBinding> copy = Map.copyOf(values);
        copy.forEach((environment, binding) -> {
            if (!environment.equals(binding.environment())) {
                throw new IllegalArgumentException("NATIVE environment key does not match binding");
            }
        });
        return copy;
    }

    private void recordReadiness() {
        if (telemetry == null) {
            return;
        }
        environments.forEach((environment, binding) ->
                telemetry.recordReadiness(environment, binding.engine().readiness()));
    }
}
