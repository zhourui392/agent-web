package com.example.agentweb.infra.nativeagent;

import com.anthropic.agentkit.interfaces.engine.DiagnoseEngine;
import com.anthropic.agentkit.interfaces.engine.RunRequest;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentRunInvocation;
import com.example.agentweb.app.agentrun.port.AgentRuntime;
import com.example.agentweb.app.agentrun.port.HistoryDeliveryMode;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeEventType;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.runtime.port.RuntimeState;
import com.example.agentweb.app.runtime.port.RuntimeTerminationReason;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
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
    private final Map<String, NativeDiagnosisEnvironmentBinding> profiles;
    private final Map<String, Map<String, NativeDiagnosisEnvironmentBinding>> profileModels;
    private final Map<String, DiagnoseEngine> activeEngines = new ConcurrentHashMap<>();
    private final Map<String, NativeExecution> executions = new ConcurrentHashMap<>();
    private final ExecutorService executionExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "native-diagnosis-runtime");
        thread.setDaemon(true);
        return thread;
    });
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
        this.profiles = Collections.emptyMap();
        this.profileModels = Collections.emptyMap();
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
        this(environments, Collections.emptyMap(), checkpoints, historyMapper,
                summaryMapper, telemetry);
    }

    /** Creates a runtime with optional Profile-specific engines keyed by profileId. */
    public NativeDiagnosisAgentRuntime(
            Map<String, NativeDiagnosisEnvironmentBinding> environments,
            Map<String, NativeDiagnosisEnvironmentBinding> profileBindings,
            DiagnosisCheckpointRepository checkpoints,
            NativeDiagnosisHistoryMapper historyMapper,
            NativeRunSummaryMapper summaryMapper,
            NativeDiagnosisTelemetry telemetry) {
        this(environments, profileBindings, Collections.emptyMap(), checkpoints,
                historyMapper, summaryMapper, telemetry);
    }

    /** Creates a runtime with Profile-specific engines keyed by profileId and model. */
    public NativeDiagnosisAgentRuntime(
            Map<String, NativeDiagnosisEnvironmentBinding> environments,
            Map<String, NativeDiagnosisEnvironmentBinding> profileBindings,
            Map<String, Map<String, NativeDiagnosisEnvironmentBinding>> profileModels,
            DiagnosisCheckpointRepository checkpoints,
            NativeDiagnosisHistoryMapper historyMapper,
            NativeRunSummaryMapper summaryMapper,
            NativeDiagnosisTelemetry telemetry) {
        this.environments = checkedBindings(environments);
        this.profiles = checkedProfileBindings(profileBindings, this.environments);
        this.profileModels = checkedProfileModels(profileModels, this.profiles,
                this.environments);
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
    public RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(sink, "sink");
        String executionId = plan.getExecutionIdentity().getExecutionId();
        NativeDiagnosisEnvironmentBinding binding = requirePlanBinding(plan);
        RuntimeHandle handle = new RuntimeHandle(executionId,
                "native-" + UUID.randomUUID());
        NativeExecution execution = new NativeExecution(handle, binding.engine(), sink);
        if (executions.putIfAbsent(handle.getHandleId(), execution) != null) {
            throw new IllegalStateException("NATIVE runtime handle already exists");
        }
        execution.emit(RuntimeEventType.STARTED, "native runtime started");
        executionExecutor.execute(() -> executePlan(plan, binding, execution));
        return handle;
    }

    @Override
    public void requestStop(RuntimeHandle handle) {
        if (handle == null) {
            return;
        }
        NativeExecution execution = executions.get(handle.getHandleId());
        if (execution == null) {
            return;
        }
        if (execution.requestStop()) {
            execution.engine().stop(handle.getExecutionId());
            execution.emit(RuntimeEventType.STOP_REQUESTED, "native runtime stop requested");
        }
    }

    @Override
    public RuntimeObservation observe(RuntimeHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("runtime handle is required");
        }
        NativeExecution execution = executions.get(handle.getHandleId());
        return execution == null ? RuntimeObservation.notFound(handle) : execution.observe();
    }

    private void executePlan(AgentExecutionPlan plan,
                             NativeDiagnosisEnvironmentBinding binding,
                             NativeExecution execution) {
        String conversationId = plan.getExecutionIdentity().getConversationId();
        long userMessageId = plan.getExecutionIdentity().getUserMessageId();
        String stateSnapshot = conversationId == null || userMessageId < 1L
                ? "" : checkpoints.findLatestValidBefore(conversationId, userMessageId)
                .map(checkpoint -> checkpoint.getStateSnapshot()).orElse("");
        RunRequest request = RunRequest.builder()
                .workingDir(plan.getWorkspaceLayout().getPrimaryRepositoryRoot())
                .userMessage(plan.getPromptPayload().getFinalPrompt())
                .sessionId(execution.getHandle().getExecutionId())
                .operationalContext(binding.operationalContext())
                .timeoutSeconds(binding.effectiveTimeout(
                        plan.getRuntimeLimits().getTimeout().toSeconds()))
                .history(historyMapper.map(plan.getPromptPayload().getTypedHistory()))
                .stateSnapshot(stateSnapshot)
                .build();
        try {
            binding.engine().run(request, chunk -> execution.emitOutput(chunk), summary -> {
                RuntimeTerminationReason reason = execution.isStopRequested()
                        ? RuntimeTerminationReason.REQUESTED_STOP
                        : summary.reason().name().equals("SUCCESS")
                        ? RuntimeTerminationReason.COMPLETED
                        : RuntimeTerminationReason.PROCESS_FAILURE;
                execution.terminate(summary.reason() == com.anthropic.agentkit.interfaces.engine.ExitReason.SUCCESS
                        ? 0 : -1, reason);
            });
        } catch (RuntimeException failure) {
            execution.terminate(-1, execution.isStopRequested()
                    ? RuntimeTerminationReason.REQUESTED_STOP
                    : RuntimeTerminationReason.PROCESS_FAILURE);
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
        executionExecutor.shutdownNow();
        java.util.stream.Stream.concat(
                        java.util.stream.Stream.concat(
                                environments.values().stream(), profiles.values().stream()),
                        profileModels.values().stream()
                                .flatMap(values -> values.values().stream()))
                .map(NativeDiagnosisEnvironmentBinding::engine)
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

    private NativeDiagnosisEnvironmentBinding requirePlanBinding(AgentExecutionPlan plan) {
        String profileId = plan.getRuntimeSelection().getProfileId();
        if (profileId != null && !profileId.isBlank()) {
            NativeDiagnosisEnvironmentBinding binding = profiles.get(profileId.trim());
            if (binding == null) {
                throw new IllegalArgumentException(
                        "NATIVE runtime Profile is not bound: " + profileId.trim());
            }
            String model = plan.getRuntimeSelection().getModel();
            if (model != null && !model.isBlank()) {
                Map<String, NativeDiagnosisEnvironmentBinding> models =
                        profileModels.get(profileId.trim());
                if (models != null && !models.isEmpty()) {
                    NativeDiagnosisEnvironmentBinding modelBinding = models.get(model.trim());
                    if (modelBinding == null) {
                        throw new IllegalArgumentException(
                                "NATIVE runtime Profile model is not bound: " + model.trim());
                    }
                    return modelBinding;
                }
            }
            return binding;
        }
        return requireEnvironment(plan.getRuntimeSelection().getRuntimeEnvironment());
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

    private static Map<String, NativeDiagnosisEnvironmentBinding> checkedProfileBindings(
            Map<String, NativeDiagnosisEnvironmentBinding> values,
            Map<String, NativeDiagnosisEnvironmentBinding> environments) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, NativeDiagnosisEnvironmentBinding> copy = Map.copyOf(values);
        copy.forEach((profileId, binding) -> {
            if (profileId == null || profileId.isBlank() || binding == null
                    || !environments.containsKey(binding.environment())) {
                throw new IllegalArgumentException(
                        "NATIVE Profile binding must reference a bound environment");
            }
        });
        return copy;
    }

    private static Map<String, Map<String, NativeDiagnosisEnvironmentBinding>>
            checkedProfileModels(
                    Map<String, Map<String, NativeDiagnosisEnvironmentBinding>> values,
                    Map<String, NativeDiagnosisEnvironmentBinding> profiles,
                    Map<String, NativeDiagnosisEnvironmentBinding> environments) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Map<String, NativeDiagnosisEnvironmentBinding>> copy =
                new java.util.LinkedHashMap<>();
        values.forEach((profileId, models) -> {
            if (profileId == null || profileId.isBlank() || !profiles.containsKey(profileId)
                    || models == null || models.isEmpty()) {
                throw new IllegalArgumentException("NATIVE Profile model binding is invalid");
            }
            Map<String, NativeDiagnosisEnvironmentBinding> modelCopy =
                    new java.util.LinkedHashMap<>();
            models.forEach((model, binding) -> {
                if (model == null || model.isBlank() || binding == null
                        || !environments.containsKey(binding.environment())) {
                    throw new IllegalArgumentException(
                            "NATIVE Profile model binding must reference a bound environment");
                }
                modelCopy.put(model.trim(), binding);
            });
            copy.put(profileId.trim(), Collections.unmodifiableMap(modelCopy));
        });
        return Collections.unmodifiableMap(copy);
    }

    private void recordReadiness() {
        if (telemetry == null) {
            return;
        }
        environments.forEach((environment, binding) ->
                telemetry.recordReadiness(environment, binding.engine().readiness()));
    }

    private static final class NativeExecution {

        private final RuntimeHandle handle;
        private final DiagnoseEngine engine;
        private final RuntimeEventSink sink;
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicLong sequence =
                new java.util.concurrent.atomic.AtomicLong();
        private volatile RuntimeState state = RuntimeState.RUNNING;
        private volatile RuntimeTerminationReason terminationReason;
        private volatile int exitCode = -1;
        private volatile long outputBytes;

        private NativeExecution(RuntimeHandle handle, DiagnoseEngine engine,
                                RuntimeEventSink sink) {
            this.handle = handle;
            this.engine = engine;
            this.sink = sink;
        }

        private RuntimeHandle getHandle() {
            return handle;
        }

        private DiagnoseEngine engine() {
            return engine;
        }

        private boolean requestStop() {
            if (terminated.get()) {
                return false;
            }
            stopRequested.set(true);
            state = RuntimeState.STOP_REQUESTED;
            return true;
        }

        private boolean isStopRequested() {
            return stopRequested.get();
        }

        private synchronized void emit(RuntimeEventType type, String payload) {
            sink.onEvent(new RuntimeEvent(handle.getExecutionId(),
                    sequence.incrementAndGet(), type,
                    payload == null ? "" : payload));
        }

        private void emitOutput(String chunk) {
            String safe = chunk == null ? "" : chunk;
            outputBytes += safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (!safe.isBlank()) {
                emit(RuntimeEventType.OUTPUT, safe.length() > RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH
                        ? safe.substring(0, RuntimeEvent.MAX_SAFE_PAYLOAD_LENGTH) : safe);
            }
        }

        private void terminate(int exitCode, RuntimeTerminationReason reason) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            this.exitCode = exitCode;
            this.terminationReason = reason;
            this.state = RuntimeState.TERMINATED;
            emit(RuntimeEventType.TERMINATED, "native runtime terminated: " + reason.name().toLowerCase());
        }

        private RuntimeObservation observe() {
            if (state == RuntimeState.RUNNING) {
                return RuntimeObservation.running(handle, outputBytes);
            }
            if (state == RuntimeState.STOP_REQUESTED) {
                return RuntimeObservation.stopRequested(handle, outputBytes);
            }
            return RuntimeObservation.terminated(handle, exitCode, terminationReason, outputBytes);
        }
    }
}
