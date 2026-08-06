package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.ExecutionPlanProviderRegistry;
import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 通过公共 Runtime 启动已持久化 ChatRun，并在开放事件回调前持久化 RuntimeHandle。
 *
 * <p>该实现暂不作为 Spring Component 自动装配，避免与 legacy ChatRunExecutor 形成双 Bean；
 * 正式切换由独立装配切片完成。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Slf4j
public final class ChatRunRuntimeLauncher implements ChatRunLauncher {

    private final ChatRunRepository runRepository;
    private final ExecutionPlanProviderRegistry planProviderRegistry;
    private final AgentExecutionGateway executionGateway;
    private final ChatRunRuntimeHandleStore handleStore;
    private final ChatRunLifecycleService lifecycleService;
    private final ChatRunRuntimeTerminationReconciler terminationReconciler;
    private final Clock clock;
    private final Executor executor;

    public ChatRunRuntimeLauncher(ChatRunRepository runRepository,
                                  ExecutionPlanProviderRegistry planProviderRegistry,
                                  AgentExecutionGateway executionGateway,
                                  ChatRunRuntimeHandleStore handleStore,
                                  ChatRunLifecycleService lifecycleService,
                                  ChatRunRuntimeTerminationReconciler
                                          terminationReconciler,
                                  Clock clock,
                                  Executor executor) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.planProviderRegistry = Objects.requireNonNull(
                planProviderRegistry, "planProviderRegistry");
        this.executionGateway = Objects.requireNonNull(
                executionGateway, "executionGateway");
        this.handleStore = Objects.requireNonNull(handleStore, "handleStore");
        this.lifecycleService = Objects.requireNonNull(
                lifecycleService, "lifecycleService");
        this.terminationReconciler = Objects.requireNonNull(
                terminationReconciler, "terminationReconciler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void launch(final ChatRunId runId) {
        final ChatRunId requiredRunId = Objects.requireNonNull(
                runId, "runId");
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    execute(requiredRunId);
                }
            });
        } catch (RuntimeException failure) {
            logFailure("runtime-launch-rejected", requiredRunId, failure);
            safeFail(requiredRunId, "RUNTIME_LAUNCH_REJECTED",
                    "Runtime 任务排队失败，请稍后重试");
        }
    }

    private void execute(ChatRunId requiredRunId) {
        ChatRun run;
        AgentExecutionPlan plan;
        try {
            run = runRepository.findById(requiredRunId).orElseThrow(
                    () -> new ChatRunNotFoundException(requiredRunId.getValue()));
            plan = planProviderRegistry.prepare(run);
        } catch (RuntimeException failure) {
            logFailure("runtime-plan-prepare-failed", requiredRunId, failure);
            safeFail(requiredRunId, "RUNTIME_PLAN_PREPARATION_FAILED",
                    "Runtime 执行计划准备失败，请稍后重试");
            return;
        }

        ChatRunRuntimeEventProcessor processor = new ChatRunRuntimeEventProcessor(
                requiredRunId, lifecycleService, executionGateway,
                terminationReconciler);
        DeferredFencedRuntimeEventSink sink = new DeferredFencedRuntimeEventSink(
                requiredRunId, handleStore, processor);
        RuntimeHandle handle;
        try {
            handle = Objects.requireNonNull(
                    executionGateway.start(plan, sink), "runtime handle");
        } catch (RuntimeException failure) {
            sink.reject();
            logFailure("runtime-start-failed", requiredRunId, failure);
            safeFail(requiredRunId, "RUNTIME_START_FAILED",
                    "Runtime 启动失败，请稍后重试");
            return;
        }

        try {
            handleStore.bind(requiredRunId, handle, clock.instant());
        } catch (RuntimeException failure) {
            sink.reject();
            logFailure("runtime-handle-bind-failed", requiredRunId, failure);
            safeRequestStop(requiredRunId, handle);
            safeFail(requiredRunId, "RUNTIME_HANDLE_BIND_FAILED",
                    "Runtime 状态绑定失败，任务已停止");
            return;
        }

        try {
            sink.activate(handle);
        } catch (RuntimeException failure) {
            sink.reject();
            logFailure("runtime-event-activation-failed", requiredRunId, failure);
            safeRequestStop(requiredRunId, handle);
            safeFail(requiredRunId, "RUNTIME_EVENT_PERSIST_FAILED",
                    "Runtime 事件保存失败，任务已停止");
        }
    }

    private void safeRequestStop(ChatRunId runId, RuntimeHandle handle) {
        try {
            executionGateway.requestStop(handle);
        } catch (RuntimeException failure) {
            logFailure("runtime-stop-after-launch-failure", runId, failure);
        }
    }

    private void safeFail(ChatRunId runId, String failureCode, String publicMessage) {
        try {
            lifecycleService.fail(runId, failureCode, publicMessage, null);
        } catch (RuntimeException failure) {
            logFailure("runtime-failure-finalize-failed", runId, failure);
        }
    }

    private void logFailure(String action, ChatRunId runId, RuntimeException failure) {
        log.error("{} runId={} failureType={} message={}", action, runId.getValue(),
                failure.getClass().getSimpleName(), failure.getMessage(), failure);
    }
}
