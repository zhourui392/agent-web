package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeTermination;
import com.example.agentweb.app.runtime.port.RuntimeTerminationReason;
import com.example.agentweb.domain.chatrun.ChatRunId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 持久化 Runtime 输出驱动的终态恢复测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class DefaultChatRunRuntimeTerminationReconcilerTest {

    private static final ChatRunId RUN_ID = ChatRunId.of("run-reconcile");
    private static final RuntimeHandle HANDLE =
            new RuntimeHandle("run-reconcile", "handle-reconcile");

    private ChatRunLifecycleService lifecycleService;
    private ChatRunRuntimeOutputQuery outputQuery;
    private DefaultChatRunRuntimeTerminationReconciler reconciler;

    @BeforeEach
    void setUp() {
        lifecycleService = mock(ChatRunLifecycleService.class);
        outputQuery = mock(ChatRunRuntimeOutputQuery.class);
        reconciler = new DefaultChatRunRuntimeTerminationReconciler(
                lifecycleService, outputQuery);
    }

    @Test
    void completedRuntimeShouldUseOnlyCompletePersistedOutput() {
        when(outputQuery.load(RUN_ID, HANDLE)).thenReturn(
                RecoveredRuntimeOutput.complete("first\nsecond"));

        reconciler.reconcile(RUN_ID, HANDLE, termination(
                0, RuntimeTerminationReason.COMPLETED));

        verify(lifecycleService).complete(
                RUN_ID, "first\nsecond", 0, null);
    }

    @Test
    void incompletePersistedOutputShouldFailClosedInsteadOfFabricatingSuccess() {
        when(outputQuery.load(RUN_ID, HANDLE)).thenReturn(
                RecoveredRuntimeOutput.incomplete());

        reconciler.reconcile(RUN_ID, HANDLE, termination(
                0, RuntimeTerminationReason.COMPLETED));

        verify(lifecycleService).fail(
                RUN_ID, "RUNTIME_RECOVERY_OUTPUT_INCOMPLETE",
                "Runtime 输出恢复不完整，任务已停止", 0);
        verify(lifecycleService, never()).complete(
                RUN_ID, "", 0, null);
    }

    @Test
    void requestedStopShouldDelegateCancellationDecisionWithoutOutput() {
        reconciler.reconcile(RUN_ID, HANDLE, termination(
                143, RuntimeTerminationReason.REQUESTED_STOP));

        verify(lifecycleService).complete(RUN_ID, "", 143, null);
        verifyNoInteractions(outputQuery);
    }

    @Test
    void technicalFailuresShouldMapToStablePublicFailureContracts() {
        reconciler.reconcile(RUN_ID, HANDLE, termination(
                124, RuntimeTerminationReason.TIMEOUT));
        reconciler.reconcile(RUN_ID, HANDLE, termination(
                137, RuntimeTerminationReason.OUTPUT_LIMIT));
        reconciler.reconcile(RUN_ID, HANDLE, termination(
                1, RuntimeTerminationReason.START_FAILURE));
        reconciler.reconcile(RUN_ID, HANDLE, termination(
                2, RuntimeTerminationReason.PROCESS_FAILURE));
        reconciler.reconcile(RUN_ID, HANDLE, termination(
                143, RuntimeTerminationReason.SECURITY_POLICY));

        verify(lifecycleService).fail(
                RUN_ID, "RUNTIME_TIMEOUT",
                "Agent 执行超时，任务已停止", 124);
        verify(lifecycleService).fail(
                RUN_ID, "OUTPUT_LIMIT",
                "输出超过上限，任务已停止", 137);
        verify(lifecycleService).fail(
                RUN_ID, "RUNTIME_START_FAILED",
                "Runtime 启动失败，请稍后重试", 1);
        verify(lifecycleService).fail(
                RUN_ID, "RUNTIME_PROCESS_FAILED",
                "Agent 执行失败，请稍后重试", 2);
        verify(lifecycleService).fail(
                RUN_ID, "HIGH_IMPACT_OPERATION_BLOCKED",
                "高影响操作未获得类型化授权，任务已停止", 143);
        verifyNoInteractions(outputQuery);
    }

    private static RuntimeTermination termination(
            int exitCode, RuntimeTerminationReason reason) {
        return new RuntimeTermination(exitCode, reason);
    }
}
