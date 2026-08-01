package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRecoveryDecision;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Workbench 恢复对账指标的来源隔离测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchChatRunRecoveryObserverTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T18:30:00Z");

    @Test
    void workbenchRecoveryShouldRecordDecisionAndFailureFacts() {
        WorkbenchTelemetry telemetry = mock(WorkbenchTelemetry.class);
        WorkbenchChatRunRecoveryObserver observer =
                new WorkbenchChatRunRecoveryObserver(telemetry);
        ChatRun run = workbenchRun();

        observer.reconciled(
                run, ChatRunRecoveryDecision.FINALIZE_TERMINATION);
        observer.failed(run);

        verify(telemetry).recoveryReconciliation(
                "FINALIZE_TERMINATION");
        verify(telemetry).recoveryReconciliation("FAILED");
    }

    @Test
    void chatRecoveryShouldNotEmitWorkbenchMetrics() {
        WorkbenchTelemetry telemetry = mock(WorkbenchTelemetry.class);
        WorkbenchChatRunRecoveryObserver observer =
                new WorkbenchChatRunRecoveryObserver(telemetry);
        ChatRun run = ChatRun.submit(
                ChatRunId.of("chat-run"), "session-chat", 1L,
                "key-chat", NOW);

        observer.reconciled(run, ChatRunRecoveryDecision.INTERRUPT);
        observer.failed(run);

        verifyNoInteractions(telemetry);
    }

    private ChatRun workbenchRun() {
        return ChatRun.submit(
                ChatRunId.of("workbench-run"), "session-workbench", 1L,
                "key-workbench", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:IMPLEMENT_TEST", "workbench-run"),
                NOW);
    }
}
