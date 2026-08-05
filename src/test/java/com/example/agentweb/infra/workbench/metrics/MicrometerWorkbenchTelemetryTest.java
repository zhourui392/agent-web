package com.example.agentweb.infra.workbench.metrics;

import com.example.agentweb.app.workbench.document.DocumentKind;
import com.example.agentweb.domain.workbench.RunMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Workbench 发布指标名称、标签和计量类型测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class MicrometerWorkbenchTelemetryTest {

    @Test
    void recordsRequiredWorkbenchReleaseMetricsWithBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerWorkbenchTelemetry telemetry =
                new MicrometerWorkbenchTelemetry(registry);

        telemetry.workbenchCreated("SUCCESS");
        telemetry.runTerminal(
                RunMode.MODIFY_WORKSPACE, "SUCCEEDED",
                Duration.ofMillis(1250L));
        telemetry.writeConflict();
        telemetry.sseReconnect("SUCCESS");
        telemetry.eventLag(Duration.ofMillis(750L));
        telemetry.capabilityResolution("SUCCESS");
        telemetry.capabilityVersionChanged();
        telemetry.workspaceScopeViolation();
        telemetry.documentRead(DocumentKind.PLAIN_TEXT, "SUCCESS");
        telemetry.recoveryReconciliation("TERMINAL_RECONCILED");

        assertEquals(1.0D, registry.get("workbench.creation")
                .tag("result", "SUCCESS").counter().count());
        assertEquals(1.0D, registry.get("workbench.run")
                .tags("mode", "MODIFY_WORKSPACE", "status", "SUCCEEDED")
                .counter().count());
        assertEquals(1L, registry.get("workbench.run.duration")
                .tag("mode", "MODIFY_WORKSPACE")
                .timer().count());
        assertEquals(1.25D, registry.get("workbench.run.duration")
                .tag("mode", "MODIFY_WORKSPACE")
                .timer().totalTime(java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1.0D, registry.get("workbench.write.conflict")
                .counter().count());
        assertEquals(1.0D, registry.get("workbench.sse.reconnect")
                .tag("result", "SUCCESS").counter().count());
        assertEquals(0.75D, registry.get("workbench.event.lag")
                .summary().totalAmount());
        assertEquals(1.0D, registry.get("workbench.capability.resolution")
                .tag("result", "SUCCESS").counter().count());
        assertEquals(1.0D, registry.get("workbench.capability.version.change")
                .counter().count());
        assertEquals(1.0D, registry.get("workbench.workspace.scope.violation")
                .counter().count());
        assertEquals(1.0D, registry.get("workbench.document.read")
                .tags("kind", "PLAIN_TEXT", "result", "SUCCESS")
                .counter().count());
        assertEquals(1.0D, registry.get("workbench.recovery.reconciliation")
                .tag("result", "TERMINAL_RECONCILED").counter().count());
    }
}
