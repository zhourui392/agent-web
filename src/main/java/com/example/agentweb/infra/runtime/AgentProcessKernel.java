package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeEventType;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.runtime.port.RuntimeState;
import com.example.agentweb.app.runtime.port.RuntimeTerminationReason;
import com.example.agentweb.infra.AgentCliProperties;
import com.example.agentweb.infra.StreamProcessWatchdog;
import com.example.agentweb.infra.cli.BuildContext;
import com.example.agentweb.infra.cli.CliDialect;
import com.example.agentweb.domain.shared.AgentType;

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
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.Map;
import java.util.UUID;

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
    private final ScheduledExecutorService legacyWatchdogScheduler;
    private final ConcurrentMap<String, LegacyExecutionContext> legacyContexts =
            new ConcurrentHashMap<String, LegacyExecutionContext>();
    private final boolean ownsLegacyWatchdogScheduler;
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
        this.legacyWatchdogScheduler = new ScheduledThreadPoolExecutor(
                1, namedFactory("runtime-watchdog"));
        ((ScheduledThreadPoolExecutor) this.legacyWatchdogScheduler)
                .setRemoveOnCancelPolicy(true);
        this.ownsLegacyWatchdogScheduler = true;
    }

    /**
     * Compatibility-only kernel used by the package-private legacy Gateway constructor in
     * isolated process tests. Production wiring always uses the full constructor above.
     */
    public static AgentProcessKernel compatibilityKernel() {
        java.util.concurrent.ExecutorService monitor =
                java.util.concurrent.Executors.newCachedThreadPool(
                        namedFactory("runtime-compat-monitor"));
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
                    ? commandFactory.create(plan, workspace, capabilities)
                    : dialect.type() == com.example.agentweb.domain.shared.AgentType.CODEX
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
                    toolNanoTimeSource, dialect);
            ExecutionContext previous = contexts.putIfAbsent(handle.getHandleId(), context);
            if (previous != null) {
                throw new IllegalStateException("runtime handle context is already active");
            }
            writePrompt(process, plan.getPromptPayload().getFinalPrompt());
            context.emit(RuntimeEventType.STARTED, "runtime started");
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
        LegacyExecutionContext context = startLegacy(
                invocation, client, dialect, stdinMessage, processEnvironment, onChunk);
        try {
            writeLegacyStdin(context.getProcess(), client, stdinMessage);
            context.startWatchdog(client, invocation.getTimeoutSeconds());
            FutureTask<Void> reader = new FutureTask<Void>(() -> {
                readLegacyStdout(context);
                return null;
            });
            context.setReader(reader);
            monitorExecutor.execute(reader);

            int exitCode = context.getProcess().waitFor();
            context.closeWatchdog();
            if (context.isTurnEnded()) {
                reader.cancel(true);
            } else {
                awaitLegacyDrain(reader, client.getStdoutDrainGraceMs());
            }
            StreamProcessWatchdog.TimeoutReason timeout = context.getTimeoutReason();
            if (timeout != null) {
                onChunk.accept(legacyTimeoutMarker(timeout));
            }
            onComplete.accept(legacyResult(context, timeout, exitCode));
        } finally {
            context.closeWatchdog();
            FutureTask<Void> reader = context.getReader();
            if (reader != null && !reader.isDone()) {
                reader.cancel(true);
            }
            finishLegacy(context);
        }
    }

    /** Stops a legacy session through the same kernel process registry as the public path. */
    public void stopLegacy(String sessionId) {
        if (sessionId == null) {
            return;
        }
        LegacyExecutionContext context = legacyContexts.get(sessionId);
        if (context == null) {
            return;
        }
        RuntimeProcessRegistry registry = processRegistry;
        registry.markStopRequested(context.getHandle());
        terminateProcessTree(context.getProcess());
    }

    public boolean isLegacyRunning(String sessionId) {
        LegacyExecutionContext context = sessionId == null
                ? null : legacyContexts.get(sessionId);
        return context != null && context.getProcess().isAlive();
    }

    private LegacyExecutionContext startLegacy(AgentRunInvocation invocation,
                                               AgentCliProperties.Client client,
                                               CliDialect dialect,
                                               String stdinMessage,
                                               Map<String, String> processEnvironment,
                                               Consumer<String> onChunk) throws IOException {
        synchronized (legacyContexts) {
            LegacyExecutionContext existing = legacyContexts.get(invocation.getRunId());
            if (existing != null && existing.getProcess().isAlive()) {
                throw new IllegalStateException(
                        "Session already has a running process: " + invocation.getRunId());
            }
            if (existing != null) {
                legacyContexts.remove(invocation.getRunId(), existing);
            }
            List<String> command = dialect.buildCommand(BuildContext.builder()
                    .config(client)
                    .userMessage(invocation.getPrompt())
                    .resumeId(invocation.getResumeId())
                    .workingDir(invocation.getWorkingDir())
                    .build());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new java.io.File(invocation.getWorkingDir()));
            builder.redirectErrorStream(true);
            if (processEnvironment != null) {
                Map<String, String> environment = builder.environment();
                environment.clear();
                environment.putAll(processEnvironment);
            }
            Process process = builder.start();
            RuntimeHandle handle = processRegistry.register(
                    "legacy:" + invocation.getRunId() + ":" + UUID.randomUUID(),
                    process);
            LegacyExecutionContext context = new LegacyExecutionContext(
                    invocation, client, dialect, stdinMessage, onChunk,
                    process, handle);
            legacyContexts.put(invocation.getRunId(), context);
            return context;
        }
    }

    private void writeLegacyStdin(Process process,
                                  AgentCliProperties.Client client,
                                  String stdinMessage) throws IOException {
        if (!client.isStdin()) {
            process.getOutputStream().close();
            return;
        }
        try (OutputStream output = process.getOutputStream()) {
            output.write((stdinMessage == null ? "" : stdinMessage)
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private void readLegacyStdout(LegacyExecutionContext context) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getProcess().getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                context.recordActivity();
                if (line.trim().isEmpty()) {
                    continue;
                }
                long bytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
                processRegistry.addOutputBytes(context.getHandle(), bytes);
                if (context.outputBytes() > normalizeLegacyMaxOutputBytes(
                        context.getClient().getMaxOutputBytes())) {
                    context.markOutputLimited();
                    context.getOnChunk().accept("[output-limit]");
                    terminateProcessTree(context.getProcess());
                    break;
                }
                context.getOnChunk().accept(line);
                if (context.getDialect().isTurnEnd(line)) {
                    context.markTurnEnded();
                    terminateProcessTree(context.getProcess());
                    break;
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (context.getProcess().isAlive()) {
                context.getOnChunk().accept("[error] " + failure.getMessage());
            }
        }
    }

    private void awaitLegacyDrain(FutureTask<Void> reader, long graceMs)
            throws InterruptedException {
        try {
            reader.get(Math.max(1L, graceMs), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            reader.cancel(true);
        } catch (java.util.concurrent.ExecutionException ignored) {
            // Reader has already reported its own safe error marker.
        }
    }

    private AgentStreamResult legacyResult(LegacyExecutionContext context,
                                           StreamProcessWatchdog.TimeoutReason timeout,
                                           int exitCode) {
        if (timeout != null) {
            return AgentStreamResult.terminated(-1, legacyTerminationReason(timeout));
        }
        if (context.isOutputLimited()) {
            return AgentStreamResult.terminated(exitCode,
                    AgentStreamResult.TerminationReason.OUTPUT_LIMIT);
        }
        return AgentStreamResult.completed(context.isTurnEnded() ? 0 : exitCode);
    }

    private AgentStreamResult.TerminationReason legacyTerminationReason(
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

    private String legacyTimeoutMarker(StreamProcessWatchdog.TimeoutReason reason) {
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

    private long normalizeLegacyMaxOutputBytes(long value) {
        return value > 0L ? value : 10L * 1024L * 1024L;
    }

    private void finishLegacy(LegacyExecutionContext context) {
        context.closeWatchdog();
        int exitCode = exitCode(context.getProcess());
        RuntimeObservation observation = processRegistry.observe(context.getHandle());
        RuntimeTerminationReason reason = observation.getState() == RuntimeState.STOP_REQUESTED
                ? RuntimeTerminationReason.REQUESTED_STOP : RuntimeTerminationReason.COMPLETED;
        try {
            processRegistry.markTerminated(context.getHandle(), exitCode, reason);
        } finally {
            processRegistry.releaseProcess(context.getHandle());
            legacyContexts.remove(context.getInvocation().getRunId(), context);
        }
        if (context.getProcess().isAlive()) {
            terminateProcessTree(context.getProcess());
        }
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
        for (String sessionId : new ArrayList<String>(legacyContexts.keySet())) {
            stopLegacy(sessionId);
        }
        if (ownsLegacyWatchdogScheduler) {
            legacyWatchdogScheduler.shutdownNow();
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
            String line;
            while ((line = reader.readLine()) != null && reason == null) {
                ConsumeResult result = consume(context, line);
                turnFailed = turnFailed || result.isTurnFailed();
                reason = result.getStopReason();
            }
            exitCode = waitForExit(context.getProcess());
            if (reason == null && processRegistry.observe(context.getHandle()).getState()
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
        long bytes = line.getBytes(StandardCharsets.UTF_8).length + 1L;
        processRegistry.addOutputBytes(context.getHandle(), bytes);
        if (processRegistry.observe(context.getHandle()).getObservedOutputBytes()
                > context.getPlan().getRuntimeLimits().getMaxOutputBytes()) {
            return ConsumeResult.stop(RuntimeTerminationReason.OUTPUT_LIMIT);
        }
        RuntimeEventDecoder.DecodedEvent decoded = context.decodeAndEmit(eventDecoder, line);
        if (decoded.getEvent() == null) {
            return ConsumeResult.output(false);
        }
        if (decoded.isOperationBlocked()) {
            return ConsumeResult.stop(RuntimeTerminationReason.SECURITY_POLICY);
        }
        if (context.getDialect() != null && context.getDialect().isTurnEnd(line)) {
            return ConsumeResult.stop(decoded.isTurnFailed()
                    ? RuntimeTerminationReason.PROCESS_FAILURE
                    : RuntimeTerminationReason.COMPLETED);
        }
        return ConsumeResult.output(decoded.isTurnFailed());
    }

    private RuntimeTerminationReason technicalStopReason(ExecutionContext context) {
        RuntimeObservation observation = processRegistry.observe(context.getHandle());
        if (observation.getState() == RuntimeState.STOP_REQUESTED) {
            return RuntimeTerminationReason.REQUESTED_STOP;
        }
        long elapsed = System.nanoTime() - context.getStartedNanos();
        if (elapsed >= context.getPlan().getRuntimeLimits().getTimeout().toNanos()) {
            return RuntimeTerminationReason.TIMEOUT;
        }
        return null;
    }

    private RuntimeTerminationReason requestedOrFailure(ExecutionContext context) {
        return processRegistry.observe(context.getHandle()).getState()
                == RuntimeState.STOP_REQUESTED
                ? RuntimeTerminationReason.REQUESTED_STOP
                : RuntimeTerminationReason.PROCESS_FAILURE;
    }

    private void complete(ExecutionContext context, int exitCode,
                          RuntimeTerminationReason reason) {
        context.releaseTiming();
        context.getCapabilities().close();
        RuntimeCleanup.CleanupResult cleanupResult = cleanup.cleanup(
                context.getWorkspace().getExecutionRoot());
        processRegistry.markTerminated(context.getHandle(), exitCode, reason);
        processRegistry.releaseProcess(context.getHandle());
        contexts.remove(context.getHandle().getHandleId(), context);
        String payload = "runtime terminated: " + reason.name().toLowerCase()
                + "; cleanup=" + (cleanupResult.isSuccessful() ? "successful" : "failed");
        context.emitSafely(RuntimeEventType.TERMINATED, payload);
    }

    private void writePrompt(Process process, String prompt) throws IOException {
        try (OutputStream output = process.getOutputStream()) {
            output.write(prompt.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.flush();
        }
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

    private final class LegacyExecutionContext {

        private final AgentRunInvocation invocation;
        private final AgentCliProperties.Client client;
        private final CliDialect dialect;
        private final String stdinMessage;
        private final Consumer<String> onChunk;
        private final Process process;
        private final RuntimeHandle handle;
        private final AtomicReference<StreamProcessWatchdog.TimeoutReason> timeoutReason =
                new AtomicReference<StreamProcessWatchdog.TimeoutReason>();
        private final AtomicBoolean turnEnded = new AtomicBoolean();
        private final AtomicBoolean outputLimited = new AtomicBoolean();
        private StreamProcessWatchdog watchdog;
        private FutureTask<Void> reader;

        private LegacyExecutionContext(AgentRunInvocation invocation,
                                       AgentCliProperties.Client client,
                                       CliDialect dialect,
                                       String stdinMessage,
                                       Consumer<String> onChunk,
                                       Process process,
                                       RuntimeHandle handle) {
            this.invocation = invocation;
            this.client = client;
            this.dialect = dialect;
            this.stdinMessage = stdinMessage;
            this.onChunk = onChunk;
            this.process = process;
            this.handle = handle;
        }

        private synchronized void startWatchdog(
                AgentCliProperties.Client configuration, long hardTimeoutSeconds) {
            boolean hardLimit = hardTimeoutSeconds > 0L;
            Duration idle = hardLimit ? Duration.ZERO
                    : durationSeconds(configuration.getStreamIdleTimeoutSeconds());
            Duration absolute = hardLimit ? durationSeconds(hardTimeoutSeconds)
                    : durationSeconds(configuration.getStreamMaxRuntimeSeconds());
            StreamProcessWatchdog.TimeoutReason absoluteReason = hardLimit
                    ? StreamProcessWatchdog.TimeoutReason.HARD_TIMEOUT
                    : StreamProcessWatchdog.TimeoutReason.MAX_RUNTIME;
            watchdog = new StreamProcessWatchdog(
                    legacyWatchdogScheduler, idle, absolute, absoluteReason, reason -> {
                timeoutReason.compareAndSet(null, reason);
                terminateProcessTree(process);
            });
        }

        private synchronized void closeWatchdog() {
            if (watchdog != null) {
                watchdog.close();
            }
        }

        private synchronized void recordActivity() {
            if (watchdog != null) {
                watchdog.recordActivity();
            }
        }

        private synchronized void setReader(FutureTask<Void> value) {
            reader = value;
        }

        private synchronized FutureTask<Void> getReader() {
            return reader;
        }

        private long outputBytes() {
            return processRegistry.observe(handle).getObservedOutputBytes();
        }

        private void markTurnEnded() {
            turnEnded.set(true);
        }

        private boolean isTurnEnded() {
            return turnEnded.get();
        }

        private void markOutputLimited() {
            outputLimited.set(true);
        }

        private boolean isOutputLimited() {
            return outputLimited.get();
        }

        private StreamProcessWatchdog.TimeoutReason getTimeoutReason() {
            return timeoutReason.get();
        }

        private AgentRunInvocation getInvocation() {
            return invocation;
        }

        private AgentCliProperties.Client getClient() {
            return client;
        }

        private CliDialect getDialect() {
            return dialect;
        }

        private Consumer<String> getOnChunk() {
            return onChunk;
        }

        private Process getProcess() {
            return process;
        }

        private RuntimeHandle getHandle() {
            return handle;
        }
    }

    private Duration durationSeconds(long seconds) {
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
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean stopEventEmitted = new AtomicBoolean();

        private ExecutionContext(AgentExecutionPlan plan, RuntimeEventSink sink,
                                 RuntimeHandle handle, Process process,
                                 RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace,
                                 RuntimeCapabilityMaterialization capabilities,
                                 long startedNanos,
                                 LongSupplier toolNanoTimeSource,
                                 CliDialect dialect) {
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
