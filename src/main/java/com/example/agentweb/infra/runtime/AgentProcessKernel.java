package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeEvent;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeEventType;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.runtime.port.RuntimeState;
import com.example.agentweb.app.runtime.port.RuntimeTerminationReason;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * Provider 中立端口下的本地 Agent 进程内核，负责异步启动、硬限额、进程树停止与清理。
 *
 * <p>单用户本机模式下 Codex 子进程直接继承服务进程环境变量（HOME、CODEX_HOME、
 * XDG_CONFIG_HOME、PATH 等），不再创建隔离的认证 HOME 或注入 Provider Key。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public final class AgentProcessKernel implements AgentExecutionGateway, AutoCloseable {

    private static final long MONITOR_INTERVAL_MILLIS = 20L;

    private final RuntimeCommandFactory commandFactory;
    private final RuntimeWorkspaceMaterializer workspaceMaterializer;
    private final RuntimeCapabilityMaterializer capabilityMaterializer;
    private final RuntimeEventDecoder eventDecoder;
    private final RuntimeProcessRegistry processRegistry;
    private final RuntimeCleanup cleanup;
    private final Executor monitorExecutor;
    private final LongSupplier toolNanoTimeSource;
    private final RuntimeAttachmentVerifier attachmentVerifier;
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
    }

    @Override
    public RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink) {
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
            List<String> command = commandFactory.create(plan, workspace, capabilities);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workspace.getPrimaryRepositoryRoot().toFile());
            builder.redirectErrorStream(true);
            processEnvironment = builder.environment();
            processEnvironment.put(
                    "AGENT_WORKBENCH_ATTACHMENT_DIR",
                    workspace.getAttachmentRoot().toString());
            attachmentVerifier.verify(plan, workspace);
            capabilities.applySecretEnvironment(processEnvironment);
            process = builder.start();
            capabilities.clearSecretEnvironment(processEnvironment);
            handle = processRegistry.register(
                    plan.getExecutionIdentity().getExecutionId(), process);
            ExecutionContext context = new ExecutionContext(plan, sink, handle, process,
                    workspace, capabilities, System.nanoTime(),
                    toolNanoTimeSource);
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

    @Override
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

    @Override
    public RuntimeObservation observe(RuntimeHandle handle) {
        return processRegistry.observe(handle);
    }

    @Override
    public void close() {
        for (RuntimeHandle handle : processRegistry.activeHandles()) {
            requestStop(handle);
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
        if (decoded.isOperationBlocked()) {
            return ConsumeResult.stop(RuntimeTerminationReason.SECURITY_POLICY);
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

    private static final class ExecutionContext {

        private final AgentExecutionPlan plan;
        private final RuntimeEventSink sink;
        private final RuntimeHandle handle;
        private final Process process;
        private final RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace;
        private final RuntimeCapabilityMaterialization capabilities;
        private final long startedNanos;
        private final RuntimeToolTimingTracker toolTiming;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean stopEventEmitted = new AtomicBoolean();

        private ExecutionContext(AgentExecutionPlan plan, RuntimeEventSink sink,
                                 RuntimeHandle handle, Process process,
                                 RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace,
                                 RuntimeCapabilityMaterialization capabilities,
                                 long startedNanos,
                                 LongSupplier toolNanoTimeSource) {
            this.plan = plan;
            this.sink = sink;
            this.handle = handle;
            this.process = process;
            this.workspace = workspace;
            this.capabilities = capabilities;
            this.startedNanos = startedNanos;
            this.toolTiming = new RuntimeToolTimingTracker(
                    handle.getExecutionId(), toolNanoTimeSource);
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
                    capabilities, plan.getWorkspaceLayout());
            sink.onEvent(toolTiming.enhance(decoded.getEvent()));
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
