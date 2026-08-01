package com.example.agentweb.app.workbench.port;

import com.example.agentweb.app.workbench.document.DocumentKind;
import com.example.agentweb.domain.workbench.HighImpactOperationType;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchPhase;

import java.time.Duration;

/**
 * Workbench 运行观测端口；Application 只报告已确定事实，不依赖具体指标后端。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchTelemetry {

    void workbenchCreated(String result);

    void runTerminal(
            WorkbenchPhase phase, RunMode mode,
            String status, Duration duration);

    void writeConflict();

    void sseReconnect(String result);

    void eventLag(Duration lag);

    void capabilityResolution(String result);

    void capabilityVersionChanged();

    void workspaceScopeViolation();

    void documentRead(DocumentKind kind, String result);

    void handoffConflict();

    void operation(HighImpactOperationType type, String status);

    void recoveryReconciliation(String result);
}
