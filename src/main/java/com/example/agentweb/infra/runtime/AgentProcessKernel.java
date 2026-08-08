package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.app.runtime.port.ExecutionIdentity;
import com.example.agentweb.app.runtime.port.HistoryDelivery;
import com.example.agentweb.app.runtime.port.PromptPayload;
import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeEventType;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.runtime.port.RuntimeState;
import com.example.agentweb.app.runtime.port.RuntimeTerminationReason;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.infra.AgentCliProperties;
import com.example.agentweb.infra.StreamProcessWatchdog;
import com.example.agentweb.infra.cli.BuildContext;
import com.example.agentweb.infra.cli.CliDialect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.Map;

import com.example.agentweb.app.agentrun.port.AgentRunInvocation;

/**
 * Provider 中立端口下的本地 Agent 进程内核，负责异步启动、硬限额、进程树停止与清理。
 *
 * <p>子进程继承服务进程的 CLI 登录态；若 Profile 显式配置 API Key，则仅在
 * {@code ProcessBuilder.start()} 前注入方言要求的环境变量，并在启动后清理父进程映射。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public final class AgentProcessKernel implements AutoCloseable {

    private static final long MONITOR_INTERVAL_MILLIS = 20L;

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return runnable -> {
            Thread thread = new Thread(runnable,
                    prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private final RuntimeCommandFactory commandFactory;
    private final RuntimeWorkspaceMaterializer workspaceMaterializer;
    private final RuntimeCapabilityMaterializer capabilityMaterializer;
    private final RuntimeEventDecoder eventDecoder;
    private final RuntimeProcessRegistry processRegistry;
    private final RuntimeCleanup cleanup;
    private final Executor monitorExecutor;
    private final LongSupplier toolNanoTimeSource;
    private final RuntimeAttachmentVerifier attachmentVerifier;
    private final ScheduledExecutorService watchdogScheduler;
    private final boolean ownsWatchdogScheduler;
    private final ConcurrentMap<String, ExecutionContext> contexts =
            new ConcurrentHashMap<String, ExecutionContext>();

    public AgentProcessKernel(RuntimeCommandFactory commandFactory,
                              RuntimeWorkspaceMaterializer workspaceMaterializer,
                              RuntimeEventDecoder eventDecoder,
                              RuntimeProcessRegistry processRegistry,
                              RuntimeCleanup cleanup,
                              Executor monitorExecutor) {
        this(commandFactory, workspaceMaterializer,
                new RuntimeCapabilityMaterializer(
                        Collections::emptyList, Collections::emptyList,
                        reference -> new char[0]),
                eventDecoder, processRegistry,
                cleanup, monitorExecutor);
    }

    public AgentProcessKernel(RuntimeCommandFactory commandFactory,
                              RuntimeWorkspaceMaterializer workspaceMaterializer,
                              RuntimeCapabilityMaterializer capabilityMaterializer,
                              RuntimeEventDecoder eventDecoder,
                              RuntimeProcessRegistry processRegistry,
                              RuntimeCleanup cleanup,
                              Executor monitorExecutor) {
        this(commandFactory, workspaceMaterializer, capabilityMaterializer,
                eventDecoder, processRegistry, cleanup, monitorExecutor,
                System::nanoTime);
    }

    AgentProcessKernel(RuntimeCommandFactory commandFactory,
                       RuntimeWorkspaceMaterializer workspaceMaterializer,
                       RuntimeCapabilityMaterializer capabilityMaterializer,
                       RuntimeEventDecoder eventDecoder,
                       RuntimeProcessRegistry processRegistry,
                       RuntimeCleanup cleanup,
                       Executor monitorExecutor,
                       LongSupplier toolNanoTimeSource) {
        this.commandFactory = Objects.requireNonNull(commandFactory, "commandFactory");
        this.workspaceMaterializer = Objects.requireNonNull(
                workspaceMaterializer, "workspaceMaterializer");
        this.capabilityMaterializer = Objects.requireNonNull(
                capabilityMaterializer, "capabilityMaterializer");
        this.eventDecoder = Objects.requireNonNull(eventDecoder, "eventDecoder");
        this.processRegistry = Objects.requireNonNull(processRegistry, "processRegistry");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.monitorExecutor = Objects.requireNonNull(monitorExecutor, "monitorExecutor");
        this.toolNanoTimeSource = Objects.requireNonNull(
                toolNanoTimeSource, "toolNanoTimeSource");
        this.attachmentVerifier = new RuntimeAttachmentVerifier();
        this.watchdogScheduler = new ScheduledThreadPoolExecutor(
                1, namedFactory("runtime-watchdog"));
        ((ScheduledThreadPoolExecutor) this.watchdogScheduler)
                .setRemoveOnCancelPolicy(true);
        this.ownsWatchdogScheduler = true;
    }

    /**
     * Compatibility-only kernel used by the package-private legacy Gateway constructor in
     * isolated process tests. Production wiring always uses the full constructor above.
     */
    public static AgentProcessKernel compatibilityKernel() {
        java.util.concurrent.ExecutorService monitor =
                java.util.concurrent.Executors.newCachedThreadPool(
                        namedFactory("runtime-monitor"));
        return new AgentProcessKernel(
                new RuntimeCommandFactory("codex"),
                new RuntimeWorkspaceMaterializer(java.nio.file.Paths.get("data/runtime")),
                new RuntimeEventDecoder(new RuntimeOutputRedactor()),
                new RuntimeProcessRegistry(), new RuntimeCleanup(), monitor);
    }

    public RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink) {
        return startInternal(plan, sink, null, null, null);
    }

    /**
     * CLI Runtime 入口：由 CliAgentRuntime 选择方言和 Profile 后调用。
     * 旧的公共入口继续使用 RuntimeCommandFactory 以保持迁移期兼容。
     */
    public RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink,
                               CliDialect dialect, AgentCliProperties.Client client,
                               String apiKey) {
        if (dialect == null || client == null) {
            throw new IllegalArgumentException("CLI dialect and client are required");
        }
        return startInternal(plan, sink, dialect, client, apiKey);
    }

    private RuntimeHandle startInternal(AgentExecutionPlan plan, RuntimeEventSink sink,
                                        CliDialect dialect,
                                        AgentCliProperties.Client client,
                                        String apiKey) {
        return startInternal(plan, sink, dialect, client, apiKey, null, null);
    }

    private RuntimeHandle startInternal(AgentExecutionPlan plan, RuntimeEventSink sink,
                                        CliDialect dialect,
                                        AgentCliProperties.Client client,
                                        String apiKey,
                                        Map<String, String> baseEnvironment,
                                        LegacyRuntimeBridge legacyBridge) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(sink, "sink");
        RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace = null;
        RuntimeCapabilityMaterialization capabilities = null;
        Process process = null;
        RuntimeHandle handle = null;
        java.util.Map<String, String> processEnvironment = null;
        try {
            workspace = workspaceMaterializer.materialize(plan);
            capabilities = capabilityMaterializer.materialize(plan, workspace);
            List<String> command = dialect == null
                    || (dialect.type() == com.example.agentweb.domain.shared.AgentType.CODEX
                    && legacyBridge == null)
                    ? commandFactory.create(plan, workspace, capabilities)
                    : dialect.buildCommand(BuildContext.builder()
                    .config(client)
                    .userMessage(plan.getPromptPayload().getFinalPrompt())
                    .resumeId(plan.getResumeId())
                    .workingDir(workspace.getPrimaryRepositoryRoot().toString())
                    .model(plan.getRuntimeSelection().getModel())
                    .endpoint(plan.getRuntimeSelection().getEndpoint())
                    .reasoningEffort(plan.getRuntimeSelection().getReasoningEffort())
                    .build());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workspace.getPrimaryRepositoryRoot().toFile());
            builder.redirectErrorStream(true);
            processEnvironment = builder.environment();
            if (baseEnvironment != null) {
                processEnvironment.clear();
                processEnvironment.putAll(baseEnvironment);
            }
            processEnvironment.put(
                    "AGENT_WORKBENCH_ATTACHMENT_DIR",
                    workspace.getAttachmentRoot().toString());
            if (dialect != null) {
                if (apiKey != null && !apiKey.isBlank()) {
                    processEnvironment.put(
                            dialect.credentialEnvironmentVariable(), apiKey);
                }
                if (plan.getRuntimeSelection().getEndpoint() != null
                        && dialect.endpointEnvironmentVariable() != null) {
                    processEnvironment.put(dialect.endpointEnvironmentVariable(),
                            plan.getRuntimeSelection().getEndpoint());
                }
            }
            attachmentVerifier.verify(plan, workspace);
            capabilities.applySecretEnvironment(processEnvironment);
            process = builder.start();
            capabilities.clearSecretEnvironment(processEnvironment);
            if (dialect != null) {
                if (dialect.credentialEnvironmentVariable() != null) {
                    processEnvironment.remove(dialect.credentialEnvironmentVariable());
                }
                if (dialect.endpointEnvironmentVariable() != null) {
                    processEnvironment.remove(dialect.endpointEnvironmentVariable());
                }
            }
            handle = processRegistry.register(
                    plan.getExecutionIdentity().getExecutionId(), process);
            ExecutionContext context = new ExecutionContext(plan, sink, handle, process,
                    workspace, capabilities, System.nanoTime(),
                    toolNanoTimeSource, dialect, legacyBridge,
                    watchdogScheduler, processRegistry);
            ExecutionContext previous = contexts.putIfAbsent(handle.getHandleId(), context);
            if (previous != null) {
                throw new IllegalStateException("runtime handle context is already active");
            }
            context.writeInput();
            context.emit(RuntimeEventType.STARTED, "runtime started");
            context.startWatchdog();
            monitorExecutor.execute(() -> monitor(context));
            return handle;
        } catch (Exception ex) {
            if (capabilities != null) {
                capabilities.clearSecretEnvironment(processEnvironment);
            }
            clearProfileEnvironment(processEnvironment, dialect);
            terminateProcessTree(process);
            if (handle != null) {
                try {
                    processRegistry.markTerminated(
                            handle, exitCode(process), RuntimeTerminationReason.START_FAILURE);
                } catch (RuntimeException ignored) {
                    // 启动失败仍继续清理临时目录。
                }
                processRegistry.releaseProcess(handle);
                contexts.remove(handle.getHandleId());
            }
            if (capabilities != null) {
                capabilities.close();
            }
            cleanup.cleanup(workspace == null ? null : workspace.getExecutionRoot());
            throw new IllegalStateException("Agent process could not be started", ex);
        }
    }

    /**
     * Runs the pre-Plan CLI invocation used by the legacy Chat gateway. The Gateway supplies
     * only compatibility data (resolved command dialect and sanitized environment); process
     * creation, reader, watchdog, stop and cleanup remain owned by this kernel.
     */
    public void runLegacy(AgentRunInvocation invocation,
                          AgentCliProperties.Client client,
                          CliDialect dialect,
                          String stdinMessage,
                          Map<String, String> processEnvironment,
                          Consumer<String> onChunk,
                          Consumer<AgentStreamResult> onComplete)
            throws IOException, InterruptedException {
        Objects.requireNonNull(invocation, "legacy invocation");
        Objects.requireNonNull(client, "CLI client");
        Objects.requireNonNull(dialect, "CLI dialect");
        Objects.requireNonNull(onChunk, "legacy chunk callback");
        Objects.requireNonNull(onComplete, "legacy completion callback");
        LegacyRuntimeBridge bridge = new LegacyRuntimeBridge(
                client, stdinMessage,
                invocation.getTimeoutSeconds(), onChunk, onComplete);
        AgentExecutionPlan plan = legacyPlan(invocation, dialect, client);
        startInternal(plan, event -> { }, dialect, client, null,
                processEnvironment, bridge);
        try {
            bridge.await();
        } catch (InterruptedException interrupted) {
            requestStop(processRegistry.activeHandle(invocation.getRunId()).orElse(null));
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    /** Stops a legacy session through the same kernel process registry as the public path. */
    public void stopLegacy(String sessionId) {
        if (sessionId == null) {
            return;
        }
        RuntimeHandle handle = processRegistry.activeHandle(sessionId).orElse(null);
        requestStop(handle);
    }

    public boolean isLegacyRunning(String sessionId) {
        RuntimeHandle handle = sessionId == null ? null
                : processRegistry.activeHandle(sessionId).orElse(null);
        return handle != null && processRegistry.process(handle)
                .map(Process::isAlive).orElse(false);
    }

    private AgentExecutionPlan legacyPlan(AgentRunInvocation invocation,
                                          CliDialect dialect,
                                          AgentCliProperties.Client client) {
        java.nio.file.Path workingDir = java.nio.file.Paths.get(invocation.getWorkingDir())
                .toAbsolutePath().normalize();
        String prompt = invocation.getPrompt().isBlank() ? " " : invocation.getPrompt();
        long timeoutSeconds = invocation.getTimeoutSeconds() > 0L
                ? invocation.getTimeoutSeconds() : client.getStreamMaxRuntimeSeconds();
        Duration timeout = timeoutSeconds > 0L
                ? Duration.ofSeconds(timeoutSeconds) : Duration.ofDays(3650L);
        String ownerId = invocation.getUserId() == null
                || invocation.getUserId().isBlank() ? "legacy" : invocation.getUserId();
        return new AgentExecutionPlan(
                new ExecutionIdentity(invocation.getRunId(),
                        ownerId,
                        "legacy:" + invocation.getRunId(),
                        invocation.getConversationId(), invocation.getUserMessageId()),
                new RuntimeSelection(null, dialect.type(), null, null, null,
                        invocation.getEnv(), RuntimeVersionPolicy.configured()),
                new PromptPayload(prompt, CanonicalHashing.sha256(prompt),
                        HistoryDelivery.PROMPT_PREFIX),
                new WorkspaceLayout(workingDir.toString(),
                        Collections.singletonList(workingDir.toString()),
                        Collections.singletonList(workingDir.toString()),
                        SandboxMode.WORKSPACE_WRITE),
                ResolvedCapabilityBinding.resolve("legacy@1", "legacy", "1.0.0",
                        CanonicalHashing.sha256("legacy"), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), dialect.type().name()),
                new RuntimeLimits(timeout, normalizeLegacyMaxOutputBytes(
                        client.getMaxOutputBytes())));
    }

    private long normalizeLegacyMaxOutputBytes(long value) {
        return value > 0L ? value : 10L * 1024L * 1024L;
    }

    private void clearProfileEnvironment(java.util.Map<String, String> environment,
                                         CliDialect dialect) {
        if (environment == null || dialect == null) {
            return;
        }
        if (dialect.credentialEnvironmentVariable() != null) {
            environment.remove(dialect.credentialEnvironmentVariable());
        }
        if (dialect.endpointEnvironmentVariable() != null) {
            environment.remove(dialect.endpointEnvironmentVariable());
        }
    }

    public void requestStop(RuntimeHandle handle) {
        if (handle == null) {
            return;
        }
        RuntimeObservation observation = processRegistry.observe(handle);
        if (observation.getState() == RuntimeState.NOT_FOUND
                || observation.getState() == RuntimeState.TERMINATED) {
            return;
        }
        ExecutionContext context = contexts.get(handle.getHandleId());
        if (context == null) {
            processRegistry.markStopRequested(handle);
        } else {
            context.markStopRequested(processRegistry);
        }
        OptionalProcess.ifPresent(processRegistry.process(handle),
                AgentProcessKernel::terminateProcessTree);
    }

    public RuntimeObservation observe(RuntimeHandle handle) {
        return processRegistry.observe(handle);
    }

    @Override
    public void close() {
        for (RuntimeHandle handle : processRegistry.activeHandles()) {
            requestStop(handle);
        }
        if (ownsWatchdogScheduler) {
            watchdogScheduler.shutdownNow();
        }
    }

    private void monitor(ExecutionContext context) {
        RuntimeTerminationReason reason = null;
        boolean turnFailed = false;
        int exitCode = -1;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getProcess().getInputStream(), StandardCharsets.UTF_8))) {
            while (context.getProcess().isAlive() && reason == null) {
                while (reader.ready() && reason == null) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    ConsumeResult result = consume(context, line);
                    turnFailed = turnFailed || result.isTurnFailed();
                    reason = result.getStopReason();
                }
                if (reason == null) {
                    reason = technicalStopReason(context);
                }
                if (reason != null) {
                    terminateProcessTree(context.getProcess());
                    break;
                }
                Thread.sleep(MONITOR_INTERVAL_MILLIS);
            }
            long drainDeadline = System.nanoTime() + context.drainGraceNanos();
            while (reason == null && System.nanoTime() < drainDeadline) {
                boolean consumed = false;
                while (reader.ready() && reason == null) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    consumed = true;
                    ConsumeResult result = consume(context, line);
                    turnFailed = turnFailed || result.isTurnFailed();
                    reason = result.getStopReason();
                }
                if (reason != null) {
                    break;
                }
                if (!consumed) {
                    Thread.sleep(MONITOR_INTERVAL_MILLIS);
                }
            }
            exitCode = waitForExit(context.getProcess());
            if (reason == null) {
                reason = context.timeoutTerminationReason();
            }
            if (reason == null
                    && processRegistry.observe(context.getHandle()).getState()
                    == RuntimeState.STOP_REQUESTED) {
                reason = RuntimeTerminationReason.REQUESTED_STOP;
            }
            if (reason == null) {
                if (turnFailed || exitCode != 0) {
                    reason = RuntimeTerminationReason.PROCESS_FAILURE;
                } else {
                    reason = RuntimeTerminationReason.COMPLETED;
                }
            }
            if (reason == RuntimeTerminationReason.COMPLETED) {
                // Codex emits turn.completed before its process has necessarily exited. The
                // one-second drain guard may therefore terminate a still-lingering process,
                // yielding 137 even though the turn itself completed successfully.
                exitCode = 0;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            reason = requestedOrFailure(context);
            terminateProcessTree(context.getProcess());
            exitCode = waitForExitQuietly(context.getProcess());
        } catch (IOException | RuntimeException ex) {
            reason = requestedOrFailure(context);
            terminateProcessTree(context.getProcess());
            exitCode = waitForExitQuietly(context.getProcess());
        } finally {
            complete(context, exitCode, reason == null
                    ? RuntimeTerminationReason.PROCESS_FAILURE : reason);
        }
    }

    private ConsumeResult consume(ExecutionContext context, String line) {
        context.recordActivity();
        if (context.isLegacy() && line.trim().isEmpty()) {
            return ConsumeResult.output(false);
        }
        long bytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
        processRegistry.addOutputBytes(context.getHandle(), bytes);
        if (processRegistry.observe(context.getHandle()).getObservedOutputBytes()
                > context.getPlan().getRuntimeLimits().getMaxOutputBytes()) {
            context.markOutputLimited();
            return ConsumeResult.stop(RuntimeTerminationReason.OUTPUT_LIMIT);
        }
        context.emitLegacyChunk(line);
        RuntimeEventDecoder.DecodedEvent decoded = context.decodeAndEmit(eventDecoder, line);
        boolean turnEnd = context.getDialect() != null
                && context.getDialect().isTurnEnd(line);
        if (turnEnd) {
            context.markTurnEnded();
            return ConsumeResult.stop(decoded.isTurnFailed()
                    ? RuntimeTerminationReason.PROCESS_FAILURE
                    : RuntimeTerminationReason.COMPLETED);
        }
        if (decoded.getEvent() == null) {
            return ConsumeResult.output(false);
        }
        if (decoded.isOperationBlocked()) {
            return ConsumeResult.stop(RuntimeTerminationReason.SECURITY_POLICY);
        }
        return ConsumeResult.output(decoded.isTurnFailed());
    }

    private RuntimeTerminationReason technicalStopReason(ExecutionContext context) {
        RuntimeTerminationReason timeout = context.timeoutTerminationReason();
        if (timeout != null) {
            return timeout;
        }
        RuntimeObservation observation = processRegistry.observe(context.getHandle());
        if (observation.getState() == RuntimeState.STOP_REQUESTED) {
            return RuntimeTerminationReason.REQUESTED_STOP;
        }
        long elapsed = System.nanoTime() - context.getStartedNanos();
        if (elapsed >= context.getPlan().getRuntimeLimits().getTimeout().toNanos()) {
            context.markPlanTimeout();
            return RuntimeTerminationReason.TIMEOUT;
        }
        return null;
    }

    private RuntimeTerminationReason requestedOrFailure(ExecutionContext context) {
        RuntimeTerminationReason timeout = context.timeoutTerminationReason();
        if (timeout != null) {
            return timeout;
        }
        return processRegistry.observe(context.getHandle()).getState()
                == RuntimeState.STOP_REQUESTED
                ? RuntimeTerminationReason.REQUESTED_STOP
                : RuntimeTerminationReason.PROCESS_FAILURE;
    }

    private void complete(ExecutionContext context, int exitCode,
                          RuntimeTerminationReason reason) {
        context.closeWatchdog();
        context.releaseTiming();
        eventDecoder.clearExecution(context.getHandle().getExecutionId());
        context.getCapabilities().close();
        RuntimeCleanup.CleanupResult cleanupResult = cleanup.cleanup(
                context.getWorkspace().getExecutionRoot());
        processRegistry.markTerminated(context.getHandle(), exitCode, reason);
        processRegistry.releaseProcess(context.getHandle());
        contexts.remove(context.getHandle().getHandleId(), context);
        String payload = "runtime terminated: " + reason.name().toLowerCase()
                + "; cleanup=" + (cleanupResult.isSuccessful() ? "successful" : "failed");
        context.emitSafely(RuntimeEventType.TERMINATED, payload);
        context.completeLegacy(exitCode);
    }

    private int waitForExit(Process process) throws InterruptedException {
        if (!process.waitFor(1L, TimeUnit.SECONDS)) {
            terminateProcessTree(process);
            process.waitFor(1L, TimeUnit.SECONDS);
        }
        return exitCode(process);
    }

    private int waitForExitQuietly(Process process) {
        try {
            return waitForExit(process);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private int exitCode(Process process) {
        if (process == null || process.isAlive()) {
            return -1;
        }
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException ex) {
            return -1;
        }
    }

    /**
     * Java 8 编译兼容的进程树强制终止；新 JDK 上通过反射使用 ProcessHandle。
     */
    public static void terminateProcessTree(Process process) {
        if (process == null) {
            return;
        }
        try {
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Object rootHandle = Process.class.getMethod("toHandle").invoke(process);
            @SuppressWarnings("unchecked")
            Stream<Object> descendants = (Stream<Object>) handleType.getMethod("descendants")
                    .invoke(rootHandle);
            List<Object> handles = new ArrayList<Object>();
            try {
                descendants.forEach(handles::add);
            } finally {
                descendants.close();
            }
            Collections.reverse(handles);
            for (Object handle : handles) {
                handleType.getMethod("destroyForcibly").invoke(handle);
            }
            handleType.getMethod("destroyForcibly").invoke(rootHandle);
        } catch (Exception ex) {
            process.destroyForcibly();
        }
    }

    private static final class LegacyRuntimeBridge {

        private final AgentCliProperties.Client client;
        private final String stdinMessage;
        private final long explicitTimeoutSeconds;
        private final Consumer<String> onChunk;
        private final Consumer<AgentStreamResult> onComplete;
        private final CountDownLatch terminal = new CountDownLatch(1);
        private final AtomicReference<StreamProcessWatchdog.TimeoutReason> timeoutReason =
                new AtomicReference<StreamProcessWatchdog.TimeoutReason>();
        private final AtomicBoolean turnEnded = new AtomicBoolean();
        private final AtomicBoolean outputLimited = new AtomicBoolean();
        private final AtomicBoolean timeoutMarkerEmitted = new AtomicBoolean();
        private final AtomicBoolean callbackCompleted = new AtomicBoolean();

        private LegacyRuntimeBridge(AgentCliProperties.Client client,
                                    String stdinMessage,
                                    long explicitTimeoutSeconds,
                                    Consumer<String> onChunk,
                                    Consumer<AgentStreamResult> onComplete) {
            this.client = client;
            this.stdinMessage = stdinMessage;
            this.explicitTimeoutSeconds = explicitTimeoutSeconds;
            this.onChunk = onChunk;
            this.onComplete = onComplete;
        }

        private boolean stdinEnabled() {
            return client.isStdin();
        }

        private String stdinMessage() {
            return stdinMessage == null ? "" : stdinMessage;
        }

        private long drainGraceNanos() {
            return TimeUnit.MILLISECONDS.toNanos(
                    Math.max(1L, client.getStdoutDrainGraceMs()));
        }

        private StreamProcessWatchdog startWatchdog(
                ScheduledExecutorService scheduler, Consumer<StreamProcessWatchdog.TimeoutReason>
                        timeoutHandler) {
            boolean hardLimit = explicitTimeoutSeconds > 0L;
            Duration idle = hardLimit ? Duration.ZERO
                    : durationSeconds(client.getStreamIdleTimeoutSeconds());
            Duration absolute = hardLimit ? durationSeconds(explicitTimeoutSeconds)
                    : durationSeconds(client.getStreamMaxRuntimeSeconds());
            StreamProcessWatchdog.TimeoutReason absoluteReason = hardLimit
                    ? StreamProcessWatchdog.TimeoutReason.HARD_TIMEOUT
                    : StreamProcessWatchdog.TimeoutReason.MAX_RUNTIME;
            return new StreamProcessWatchdog(scheduler, idle, absolute, absoluteReason,
                    reason -> {
                        timeoutReason.compareAndSet(null, reason);
                        timeoutHandler.accept(reason);
                    });
        }

        private StreamProcessWatchdog.TimeoutReason planTimeoutReason() {
            return explicitTimeoutSeconds > 0L
                    ? StreamProcessWatchdog.TimeoutReason.HARD_TIMEOUT
                    : StreamProcessWatchdog.TimeoutReason.MAX_RUNTIME;
        }

        private void emitLine(String line) {
            onChunk.accept(line);
        }

        private void markTurnEnded() {
            turnEnded.set(true);
        }

        private void markOutputLimited() {
            if (outputLimited.compareAndSet(false, true)) {
                onChunk.accept("[output-limit]");
            }
        }

        private void emitTimeoutMarker(StreamProcessWatchdog.TimeoutReason reason) {
            if (timeoutMarkerEmitted.compareAndSet(false, true)) {
                onChunk.accept(legacyTimeoutMarker(reason));
            }
        }

        private void complete(int exitCode) {
            if (!callbackCompleted.compareAndSet(false, true)) {
                return;
            }
            AgentStreamResult result;
            StreamProcessWatchdog.TimeoutReason timeout = timeoutReason.get();
            if (timeout != null) {
                result = AgentStreamResult.terminated(-1, legacyTerminationReason(timeout));
            } else if (outputLimited.get()) {
                result = AgentStreamResult.terminated(exitCode,
                        AgentStreamResult.TerminationReason.OUTPUT_LIMIT);
            } else {
                result = AgentStreamResult.completed(turnEnded.get() ? 0 : exitCode);
            }
            try {
                onComplete.accept(result);
            } finally {
                terminal.countDown();
            }
        }

        private void await() throws InterruptedException {
            terminal.await();
        }

        private static AgentStreamResult.TerminationReason legacyTerminationReason(
                StreamProcessWatchdog.TimeoutReason reason) {
            switch (reason) {
                case IDLE:
                    return AgentStreamResult.TerminationReason.IDLE_TIMEOUT;
                case MAX_RUNTIME:
                    return AgentStreamResult.TerminationReason.MAX_RUNTIME_TIMEOUT;
                case HARD_TIMEOUT:
                    return AgentStreamResult.TerminationReason.HARD_TIMEOUT;
                default:
                    throw new IllegalArgumentException("unsupported timeout reason: " + reason);
            }
        }

        private static String legacyTimeoutMarker(
                StreamProcessWatchdog.TimeoutReason reason) {
            switch (reason) {
                case IDLE:
                    return "[timeout:idle]";
                case MAX_RUNTIME:
                    return "[timeout:max-runtime]";
                case HARD_TIMEOUT:
                    return "[timeout:hard]";
                default:
                    throw new IllegalArgumentException("unsupported timeout reason: " + reason);
            }
        }
    }

    private static Duration durationSeconds(long seconds) {
        return seconds > 0L ? Duration.ofSeconds(seconds) : Duration.ZERO;
    }

    private static final class ExecutionContext {

        private final AgentExecutionPlan plan;
        private final RuntimeEventSink sink;
        private final RuntimeHandle handle;
        private final Process process;
        private final RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace;
        private final RuntimeCapabilityMaterialization capabilities;
        private final long startedNanos;
        private final RuntimeToolTimingTracker toolTiming;
        private final CliDialect dialect;
        private final LegacyRuntimeBridge legacyBridge;
        private final ScheduledExecutorService watchdogScheduler;
        private final RuntimeProcessRegistry processRegistry;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean stopEventEmitted = new AtomicBoolean();
        private final AtomicReference<StreamProcessWatchdog.TimeoutReason> timeoutReason =
                new AtomicReference<StreamProcessWatchdog.TimeoutReason>();
        private volatile StreamProcessWatchdog watchdog;

        private ExecutionContext(AgentExecutionPlan plan, RuntimeEventSink sink,
                                 RuntimeHandle handle, Process process,
                                 RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace,
                                 RuntimeCapabilityMaterialization capabilities,
                                 long startedNanos,
                                 LongSupplier toolNanoTimeSource,
                                 CliDialect dialect,
                                 LegacyRuntimeBridge legacyBridge,
                                 ScheduledExecutorService watchdogScheduler,
                                 RuntimeProcessRegistry processRegistry) {
            this.plan = plan;
            this.sink = sink;
            this.handle = handle;
            this.process = process;
            this.workspace = workspace;
            this.capabilities = capabilities;
            this.startedNanos = startedNanos;
            this.toolTiming = new RuntimeToolTimingTracker(
                    handle.getExecutionId(), toolNanoTimeSource);
            this.dialect = dialect;
            this.legacyBridge = legacyBridge;
            this.watchdogScheduler = watchdogScheduler;
            this.processRegistry = processRegistry;
        }

        private synchronized void emit(RuntimeEventType type, String payload) {
            sink.onEvent(new RuntimeEvent(handle.getExecutionId(),
                    sequence.incrementAndGet(), type, payload));
        }

        private void emitSafely(RuntimeEventType type, String payload) {
            try {
                emit(type, payload);
            } catch (RuntimeException ignored) {
                // Sink 失败不能阻断进程树终止、注册表终态或临时目录清理。
            }
        }

        private synchronized void markStopRequested(RuntimeProcessRegistry registry) {
            registry.markStopRequested(handle);
            if (stopEventEmitted.compareAndSet(false, true)) {
                emitSafely(RuntimeEventType.STOP_REQUESTED, "runtime stop requested");
            }
        }

        private synchronized RuntimeEventDecoder.DecodedEvent decodeAndEmit(
                RuntimeEventDecoder decoder, String line) {
            RuntimeEventDecoder.DecodedEvent decoded = decoder.decode(
                    handle.getExecutionId(), sequence.incrementAndGet(), line,
                    capabilities, plan.getWorkspaceLayout(), dialect);
            if (decoded.getEvent() != null) {
                sink.onEvent(toolTiming.enhance(decoded.getEvent()));
            }
            return decoded;
        }

        private void writeInput() throws IOException {
            if (legacyBridge != null) {
                if (!legacyBridge.stdinEnabled()) {
                    process.getOutputStream().close();
                    return;
                }
                try (OutputStream output = process.getOutputStream()) {
                    output.write(legacyBridge.stdinMessage().getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                return;
            }
            try (OutputStream output = process.getOutputStream()) {
                output.write(plan.getPromptPayload().getFinalPrompt()
                        .getBytes(StandardCharsets.UTF_8));
                output.write('\n');
                output.flush();
            }
        }

        private void startWatchdog() {
            if (legacyBridge == null) {
                return;
            }
            watchdog = legacyBridge.startWatchdog(
                    watchdogScheduler, reason -> {
                        markTimeout(reason);
                        terminateProcessTree(process);
                    });
        }

        private void markPlanTimeout() {
            if (legacyBridge != null) {
                markTimeout(legacyBridge.planTimeoutReason());
            }
        }

        private void markTimeout(StreamProcessWatchdog.TimeoutReason reason) {
            if (!timeoutReason.compareAndSet(null, reason)) {
                return;
            }
            if (legacyBridge != null) {
                legacyBridge.emitTimeoutMarker(reason);
            }
            processRegistry.markStopRequested(handle);
        }

        private void closeWatchdog() {
            StreamProcessWatchdog current = watchdog;
            if (current != null) {
                current.close();
            }
        }

        private void recordActivity() {
            StreamProcessWatchdog current = watchdog;
            if (current != null) {
                current.recordActivity();
            }
        }

        private void markOutputLimited() {
            if (legacyBridge != null) {
                legacyBridge.markOutputLimited();
            }
        }

        private void markTurnEnded() {
            if (legacyBridge != null) {
                legacyBridge.markTurnEnded();
            }
        }

        private void emitLegacyChunk(String line) {
            if (legacyBridge != null) {
                legacyBridge.emitLine(line);
            }
        }

        private boolean isLegacy() {
            return legacyBridge != null;
        }

        private long drainGraceNanos() {
            return legacyBridge == null ? TimeUnit.MILLISECONDS.toNanos(200L)
                    : legacyBridge.drainGraceNanos();
        }

        private RuntimeTerminationReason timeoutTerminationReason() {
            StreamProcessWatchdog.TimeoutReason timeout = timeoutReason.get();
            if (timeout == null) {
                return null;
            }
            return RuntimeTerminationReason.TIMEOUT;
        }

        private void completeLegacy(int exitCode) {
            if (legacyBridge != null) {
                legacyBridge.complete(exitCode);
            }
        }

        private void releaseTiming() {
            toolTiming.clear();
        }

        private AgentExecutionPlan getPlan() {
            return plan;
        }

        private RuntimeHandle getHandle() {
            return handle;
        }

        private Process getProcess() {
            return process;
        }

        private RuntimeWorkspaceMaterializer.MaterializedWorkspace getWorkspace() {
            return workspace;
        }

        private RuntimeCapabilityMaterialization getCapabilities() {
            return capabilities;
        }

        private CliDialect getDialect() {
            return dialect;
        }

        private long getStartedNanos() {
            return startedNanos;
        }
    }

    private static final class ConsumeResult {

        private final boolean turnFailed;
        private final RuntimeTerminationReason stopReason;

        private ConsumeResult(boolean turnFailed, RuntimeTerminationReason stopReason) {
            this.turnFailed = turnFailed;
            this.stopReason = stopReason;
        }

        private static ConsumeResult output(boolean turnFailed) {
            return new ConsumeResult(turnFailed, null);
        }

        private static ConsumeResult stop(RuntimeTerminationReason reason) {
            return new ConsumeResult(false, reason);
        }

        private boolean isTurnFailed() {
            return turnFailed;
        }

        private RuntimeTerminationReason getStopReason() {
            return stopReason;
        }
    }

    /**
     * Java 8 下避免 Optional#ifPresent 的 method reference 推断差异。
     */
    private static final class OptionalProcess {

        private OptionalProcess() {
        }

        private static void ifPresent(java.util.Optional<Process> process,
                                      java.util.function.Consumer<Process> consumer) {
            if (process.isPresent()) {
                consumer.accept(process.get());
            }
        }
    }
}
