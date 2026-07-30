package com.example.agentweb.infra.nativeagent;

import com.anthropic.agentkit.domain.diagnosis.DiagnosisBlockerType;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisPlan;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisToolMetadata;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.anthropic.agentkit.domain.tool.ToolResult;
import com.anthropic.agentkit.domain.tool.ToolResultStatus;
import com.anthropic.agentkit.domain.tool.ToolUseId;
import com.anthropic.agentkit.domain.tool.ToolUseRequest;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisStateCodec;
import com.anthropic.agentkit.infrastructure.tools.governance.ToolAuditEvent;
import com.anthropic.agentkit.interfaces.engine.DiagnosisBlockerView;
import com.anthropic.agentkit.interfaces.engine.DiagnosisCapability;
import com.anthropic.agentkit.interfaces.engine.DiagnosisMode;
import com.anthropic.agentkit.interfaces.engine.DiagnosisOutcome;
import com.anthropic.agentkit.interfaces.engine.DiagnosisReadiness;
import com.anthropic.agentkit.interfaces.engine.ExitReason;
import com.anthropic.agentkit.interfaces.engine.RunSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author alex
 */
class NativeDiagnosisTelemetryTest {

    @Test
    void exportsRequiredRunToolEvidenceBlockerAndReadinessMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NativeDiagnosisTelemetry telemetry = new NativeDiagnosisTelemetry(registry);
        telemetry.bindTool("test", "LogQuery", "local-agent-web-logs");
        telemetry.recordReadiness("test", readiness());
        NativeDiagnosisTelemetry.RunObservation observation = telemetry.start(
                "test", "run-1", "conversation-1", "");

        telemetry.auditSink("test").record(new ToolAuditEvent(
                "LogQuery", true, 25, "", "run-1", "run-1", 512));
        telemetry.complete(observation, summary(snapshot()));

        assertThat(registry.get("diagnosis.run.total")
                .tags("outcome", "COMPLETED", "environment", "test")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("diagnosis.run.duration")
                .tags("outcome", "COMPLETED", "environment", "test")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("diagnosis.plan.blocked")
                .tags("blocker_type", "BACKEND_UNHEALTHY", "code", "BACKEND_RATE_LIMITED",
                        "environment", "test").counter().count()).isEqualTo(1);
        assertThat(registry.get("diagnosis.tool.calls")
                .tags("tool", "LogQuery", "data_source", "local-agent-web-logs",
                        "status", "SUCCESS", "environment", "test")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("diagnosis.tool.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("diagnosis.tool.result.bytes").summary().totalAmount())
                .isEqualTo(512);
        assertThat(registry.get("diagnosis.evidence.count")
                .tags("source", "TOOL_RESULT", "environment", "test")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("diagnosis.query.window.seconds").summary().totalAmount())
                .isEqualTo(7200);
        assertThat(registry.get("diagnosis.backend.readiness")
                .tags("environment", "test", "data_source", "local-agent-web-logs",
                        "tool", "LogQuery").gauge().value()).isEqualTo(1);
    }

    @Test
    void synchronousFailureProducesOneFailedRunMetricWithoutSensitiveDetailTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NativeDiagnosisTelemetry telemetry = new NativeDiagnosisTelemetry(registry);
        NativeDiagnosisTelemetry.RunObservation observation = telemetry.start(
                "prod", "run-2", "conversation-2", "");

        telemetry.failed(observation,
                new IllegalStateException("Authorization: Bearer must-not-be-a-tag"));

        assertThat(registry.get("diagnosis.run.total")
                .tags("outcome", "FAILED", "environment", "prod")
                .counter().count()).isEqualTo(1);
        assertThat(registry.getMeters().toString()).doesNotContain("must-not-be-a-tag");
    }

    private DiagnosisReadiness readiness() {
        return new DiagnosisReadiness(ReadinessStatus.READY, DiagnosisMode.OPERATIONAL,
                List.of(new DiagnosisCapability("LogQuery", "local-agent-web-logs", "test",
                        ReadinessStatus.READY, Set.of("query"), "")), "");
    }

    private String snapshot() {
        DiagnosisCase diagnosisCase = DiagnosisCase.open("run-1", "recent errors");
        diagnosisCase.adoptPlan(new DiagnosisPlan("recent errors", List.of(), List.of()));
        diagnosisCase.recordToolEvidence(
                new ToolUseRequest(new ToolUseId("tool-1"), "LogQuery", "{}"),
                ToolResult.of(ToolResultStatus.SUCCESS, "one error", Map.of(
                        DiagnosisToolMetadata.QUERY_START, "2026-07-30T00:00:00Z",
                        DiagnosisToolMetadata.QUERY_END, "2026-07-30T02:00:00Z")));
        diagnosisCase.markDone();
        return new DiagnosisStateCodec().encode(diagnosisCase);
    }

    private RunSummary summary(String snapshot) {
        return new RunSummary(ExitReason.SUCCESS, snapshot, RunSummary.Usage.zero(), "",
                DiagnosisOutcome.COMPLETED, List.of(new DiagnosisBlockerView(
                DiagnosisBlockerType.BACKEND_UNHEALTHY, "BACKEND_RATE_LIMITED",
                "backend unavailable", "retry later", false)));
    }
}
