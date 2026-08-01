package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunRecoveryObserver;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunRecoveryDecision;
import com.example.agentweb.domain.chatrun.RunOrigin;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 将公共恢复对账事实投影为 Workbench 发布指标。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public final class WorkbenchChatRunRecoveryObserver
        implements ChatRunRecoveryObserver {

    private final WorkbenchTelemetry telemetry;

    public WorkbenchChatRunRecoveryObserver(WorkbenchTelemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    public void reconciled(
            ChatRun run, ChatRunRecoveryDecision decision) {
        if (isWorkbench(run)) {
            telemetry.recoveryReconciliation(decision.name());
        }
    }

    @Override
    public void failed(ChatRun run) {
        if (isWorkbench(run)) {
            telemetry.recoveryReconciliation("FAILED");
        }
    }

    private boolean isWorkbench(ChatRun run) {
        return Objects.requireNonNull(run, "run").getRunOrigin()
                == RunOrigin.WORKBENCH;
    }
}
