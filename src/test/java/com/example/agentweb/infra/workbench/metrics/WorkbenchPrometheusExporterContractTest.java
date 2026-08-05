package com.example.agentweb.infra.workbench.metrics;

import com.example.agentweb.app.workbench.document.DocumentKind;
import com.example.agentweb.domain.workbench.RunMode;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Workbench Micrometer 指标到 Prometheus exposition format 的真实导出合同。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchPrometheusExporterContractTest {

    @Test
    void shouldExportAllWorkbenchReleaseMetersWithPrometheusNamesAndTags() {
        PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench WHERE status='ACTIVE'",
                Long.class)).thenReturn(Long.valueOf(3L));
        MicrometerWorkbenchTelemetry telemetry =
                new MicrometerWorkbenchTelemetry(registry);
        // Keep a strong reference so Micrometer's weak-ref Gauge doesn't go NaN under GC.
        WorkbenchActiveGauge activeGauge = new WorkbenchActiveGauge(registry, jdbc);

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

        String scrape = registry.scrape();

        assertAll(
                () -> assertSeries(scrape,
                        "# TYPE workbench_creation_total counter",
                        "workbench_creation_total{result=\"SUCCESS\"} 1.0"),
                () -> assertSeries(scrape,
                        "# TYPE workbench_active gauge",
                        "workbench_active 3.0"),
                () -> assertSeries(scrape,
                        "# TYPE workbench_run_total counter",
                        "workbench_run_total{mode=\"MODIFY_WORKSPACE\","
                                + "status=\"SUCCEEDED\"} 1.0"),
                () -> assertSeries(scrape,
                        "# TYPE workbench_run_duration_seconds summary",
                        "workbench_run_duration_seconds_count{"
                                + "mode=\"MODIFY_WORKSPACE\"} 1",
                        "workbench_run_duration_seconds_sum{"
                                + "mode=\"MODIFY_WORKSPACE\"} 1.25",
                        "workbench_run_duration_seconds_max{"
                                + "mode=\"MODIFY_WORKSPACE\"} 1.25"),
                () -> assertSeries(scrape,
                        "workbench_write_conflict_total 1.0"),
                () -> assertSeries(scrape,
                        "workbench_sse_reconnect_total{"
                                + "result=\"SUCCESS\"} 1.0"),
                () -> assertSeries(scrape,
                        "# TYPE workbench_event_lag_seconds summary",
                        "workbench_event_lag_seconds_count 1",
                        "workbench_event_lag_seconds_sum 0.75",
                        "workbench_event_lag_seconds_max 0.75"),
                () -> assertSeries(scrape,
                        "workbench_capability_resolution_total{"
                                + "result=\"SUCCESS\"} 1.0"),
                () -> assertSeries(scrape,
                        "workbench_capability_version_change_total 1.0"),
                () -> assertSeries(scrape,
                        "workbench_workspace_scope_violation_total 1.0"),
                () -> assertSeries(scrape,
                        "workbench_document_read_total{kind=\"PLAIN_TEXT\","
                                + "result=\"SUCCESS\"} 1.0"),
                () -> assertSeries(scrape,
                        "workbench_recovery_reconciliation_total{"
                                + "result=\"TERMINAL_RECONCILED\"} 1.0"),
                () -> assertFalse(scrape.contains("workbench_total{")),
                () -> assertFalse(scrape.contains(
                        "workbench_active_total")));
    }

    private static void assertSeries(
            String scrape, String... expectedLines) {
        for (String expectedLine : expectedLines) {
            assertTrue(scrape.contains(expectedLine),
                    () -> "missing Prometheus series line: "
                            + expectedLine + "\n" + scrape);
        }
    }
}
