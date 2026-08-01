package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeTermination;
import com.example.agentweb.domain.chatrun.ChatRunId;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 基于持久化输出收口 Runtime 终态的默认实现。
 *
 * @author alex
 * @since 2026-08-01
 */
@Service
public final class DefaultChatRunRuntimeTerminationReconciler
        implements ChatRunRuntimeTerminationReconciler {

    private final ChatRunLifecycleService lifecycleService;
    private final ChatRunRuntimeOutputQuery outputQuery;

    public DefaultChatRunRuntimeTerminationReconciler(
            ChatRunLifecycleService lifecycleService,
            ChatRunRuntimeOutputQuery outputQuery) {
        this.lifecycleService = Objects.requireNonNull(
                lifecycleService, "lifecycleService");
        this.outputQuery = Objects.requireNonNull(
                outputQuery, "outputQuery");
    }

    @Override
    public void reconcile(
            ChatRunId runId, RuntimeHandle handle,
            RuntimeTermination termination) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(termination, "termination");
        int exitCode = termination.getExitCode();
        switch (termination.getReason()) {
            case COMPLETED:
                completeFromPersistedOutput(runId, handle, exitCode);
                return;
            case REQUESTED_STOP:
                lifecycleService.complete(runId, "", exitCode, null);
                return;
            case TIMEOUT:
                lifecycleService.fail(runId, "RUNTIME_TIMEOUT",
                        "Agent 执行超时，任务已停止", exitCode);
                return;
            case OUTPUT_LIMIT:
                lifecycleService.fail(runId, "OUTPUT_LIMIT",
                        "输出超过上限，任务已停止", exitCode);
                return;
            case SECURITY_POLICY:
                lifecycleService.fail(runId,
                        "HIGH_IMPACT_OPERATION_BLOCKED",
                        "高影响操作未获得类型化授权，任务已停止",
                        exitCode);
                return;
            case START_FAILURE:
                lifecycleService.fail(runId, "RUNTIME_START_FAILED",
                        "Runtime 启动失败，请稍后重试", exitCode);
                return;
            case PROCESS_FAILURE:
                lifecycleService.fail(runId, "RUNTIME_PROCESS_FAILED",
                        "Agent 执行失败，请稍后重试", exitCode);
                return;
            default:
                throw new IllegalArgumentException(
                        "unsupported runtime termination reason");
        }
    }

    private void completeFromPersistedOutput(
            ChatRunId runId, RuntimeHandle handle, int exitCode) {
        RecoveredRuntimeOutput output = outputQuery.load(runId, handle);
        if (!output.isComplete()) {
            lifecycleService.fail(
                    runId, "RUNTIME_RECOVERY_OUTPUT_INCOMPLETE",
                    "Runtime 输出恢复不完整，任务已停止", exitCode);
            return;
        }
        lifecycleService.complete(
                runId, output.getContent(), exitCode, null);
    }
}
