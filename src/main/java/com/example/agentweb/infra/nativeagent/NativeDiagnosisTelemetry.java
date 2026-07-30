package com.example.agentweb.infra.nativeagent;

import com.anthropic.agentkit.domain.diagnosis.DiagnosisCase;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisToolMetadata;
import com.anthropic.agentkit.domain.diagnosis.Evidence;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisStateCodec;
import com.anthropic.agentkit.infrastructure.tools.governance.ToolAuditEvent;
import com.anthropic.agentkit.infrastructure.tools.governance.ToolAuditSink;
import com.anthropic.agentkit.interfaces.engine.DiagnosisCapability;
import com.anthropic.agentkit.interfaces.engine.DiagnosisReadiness;
import com.anthropic.agentkit.interfaces.engine.RunSummary;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exportable diagnosis metrics and bounded structured audit projection for NATIVE runs.
 *
 * @author alex
 * @since 2026-07-30
 */
public final class NativeDiagnosisTelemetry {

    private static final Logger log = LoggerFactory.getLogger(NativeDiagnosisTelemetry.class);
    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;
    private final DiagnosisStateCodec stateCodec = new DiagnosisStateCodec();
    private final MultiGauge readinessGauge;
    private final Map<String, ToolBinding> toolBindings = new ConcurrentHashMap<>();
    private final Map<String, DiagnosisReadiness> readinessByEnvironment =
            new ConcurrentHashMap<>();

    public NativeDiagnosisTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.readinessGauge = MultiGauge.builder("diagnosis.backend.readiness")
                .description("Diagnosis backend readiness: READY=1, DEGRADED=0.5, UNAVAILABLE=0")
                .register(registry);
    }

    public void bindTool(String environment, String toolName, String dataSourceId) {
        String env = tag(environment);
        String tool = tag(toolName);
        toolBindings.put(bindingKey(env, tool), new ToolBinding(env, tool, tag(dataSourceId)));
    }

    public ToolAuditSink auditSink(String environment) {
        String env = tag(environment);
        return event -> recordToolAudit(env, event);
    }

    public RunObservation start(String environment, String runId, String sessionId,
                                String initialStateSnapshot) {
        String env = tag(environment);
        int initialEvidence = evidence(initialStateSnapshot).size();
        log.info("native-diagnosis-run-audit phase=START runId={} sessionId={} environment={} "
                        + "initialEvidence={}",
                safeId(runId), safeId(sessionId), env, initialEvidence);
        return new RunObservation(env, safeId(runId), safeId(sessionId), initialEvidence,
                Timer.start(registry));
    }

    public void complete(RunObservation observation, RunSummary summary) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(summary, "summary");
        String outcome = summary.outcome().name();
        Counter.builder("diagnosis.run.total")
                .tag("outcome", outcome)
                .tag("environment", observation.environment())
                .register(registry).increment();
        observation.timer().stop(Timer.builder("diagnosis.run.duration")
                .tag("outcome", outcome)
                .tag("environment", observation.environment())
                .register(registry));
        summary.blockers().forEach(blocker -> recordBlocker(observation.environment(), blocker));
        recordNewEvidence(observation, evidence(summary.stateSnapshot()));
        log.info("native-diagnosis-run-audit phase=COMPLETE runId={} sessionId={} environment={} "
                        + "outcome={} reason={} blockers={} inputTokens={} outputTokens={}",
                observation.runId(), observation.sessionId(), observation.environment(), outcome,
                summary.reason(), summary.blockers().size(), summary.usage().inputTokens(),
                summary.usage().outputTokens());
    }

    public void failed(RunObservation observation, RuntimeException failure) {
        Objects.requireNonNull(observation, "observation");
        Counter.builder("diagnosis.run.total")
                .tag("outcome", "FAILED")
                .tag("environment", observation.environment())
                .register(registry).increment();
        observation.timer().stop(Timer.builder("diagnosis.run.duration")
                .tag("outcome", "FAILED")
                .tag("environment", observation.environment())
                .register(registry));
        log.warn("native-diagnosis-run-audit phase=FAILED runId={} sessionId={} environment={} "
                        + "failureType={}", observation.runId(), observation.sessionId(),
                observation.environment(), failure.getClass().getSimpleName());
    }

    public synchronized void recordReadiness(String environment, DiagnosisReadiness readiness) {
        String env = tag(environment);
        readinessByEnvironment.put(env, Objects.requireNonNull(readiness, "readiness"));
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        readinessByEnvironment.forEach((boundEnvironment, boundReadiness) ->
                appendReadinessRows(rows, boundEnvironment, boundReadiness));
        readinessGauge.register(rows, true);
    }

    private void appendReadinessRows(List<MultiGauge.Row<?>> rows, String environment,
                                     DiagnosisReadiness readiness) {
        if (readiness.capabilities().isEmpty()) {
            rows.add(MultiGauge.Row.of(
                    Tags.of("environment", environment, "data_source", "none", "tool", "none"),
                    Double.valueOf(readinessValue(readiness.status().name()))));
            return;
        }
        for (DiagnosisCapability capability : readiness.capabilities()) {
            rows.add(readinessRow(environment, capability));
        }
    }

    private void recordToolAudit(String environment, ToolAuditEvent event) {
        ToolBinding binding = toolBindings.getOrDefault(
                bindingKey(environment, tag(event.toolName())),
                new ToolBinding(environment, tag(event.toolName()), UNKNOWN));
        String status = event.success() ? "SUCCESS" : "ERROR";
        Tags tags = Tags.of("tool", binding.toolName(),
                "data_source", binding.dataSourceId(), "status", status,
                "environment", binding.environment());
        Counter.builder("diagnosis.tool.calls").tags(tags).register(registry).increment();
        Timer.builder("diagnosis.tool.duration").tags(tags)
                .register(registry).record(event.durationMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
        DistributionSummary.builder("diagnosis.tool.result.bytes")
                .tags("tool", binding.toolName(), "environment", binding.environment())
                .baseUnit("bytes").register(registry).record(event.resultBytes());
        log.info("native-diagnosis-tool-audit runId={} sessionId={} environment={} "
                        + "tool={} dataSourceId={} status={} durationMs={} resultBytes={}",
                safeId(event.runId()), safeId(event.sessionId()), binding.environment(),
                binding.toolName(), binding.dataSourceId(), status,
                event.durationMs(), event.resultBytes());
    }

    private void recordBlocker(String environment,
                               com.anthropic.agentkit.interfaces.engine.DiagnosisBlockerView blocker) {
        Counter.builder("diagnosis.plan.blocked")
                .tags("blocker_type", blocker.type().name(), "code", tag(blocker.code()),
                        "environment", environment)
                .register(registry).increment();
    }

    private void recordNewEvidence(RunObservation observation, List<Evidence> all) {
        int from = Math.min(observation.initialEvidence(), all.size());
        all.subList(from, all.size()).forEach(evidence -> {
            Counter.builder("diagnosis.evidence.count")
                    .tags("source", evidence.source().name(),
                            "environment", observation.environment())
                    .register(registry).increment();
            queryWindow(evidence).ifPresent(seconds -> DistributionSummary
                    .builder("diagnosis.query.window.seconds")
                    .tags("tool", tag(evidence.toolName()),
                            "environment", observation.environment())
                    .baseUnit("seconds").register(registry).record(seconds));
        });
    }

    private java.util.OptionalDouble queryWindow(Evidence evidence) {
        try {
            Object start = evidence.metadata().get(DiagnosisToolMetadata.QUERY_START);
            Object end = evidence.metadata().get(DiagnosisToolMetadata.QUERY_END);
            if (start == null || end == null) {
                return java.util.OptionalDouble.empty();
            }
            long seconds = Duration.between(
                    Instant.parse(start.toString()), Instant.parse(end.toString())).toSeconds();
            return seconds < 0 ? java.util.OptionalDouble.empty()
                    : java.util.OptionalDouble.of(seconds);
        } catch (RuntimeException invalidWindow) {
            return java.util.OptionalDouble.empty();
        }
    }

    private List<Evidence> evidence(String snapshot) {
        return stateCodec.decode(snapshot)
                .map(DiagnosisCase::ledger)
                .map(ledger -> ledger.all())
                .orElseGet(List::of);
    }

    private MultiGauge.Row<?> readinessRow(String environment, DiagnosisCapability capability) {
        bindTool(environment, capability.toolName(), capability.dataSourceId());
        return MultiGauge.Row.of(Tags.of(
                        "environment", environment,
                        "data_source", tag(capability.dataSourceId()),
                        "tool", tag(capability.toolName())),
                Double.valueOf(readinessValue(capability.readiness().name())));
    }

    private double readinessValue(String status) {
        return switch (status) {
            case "READY" -> 1.0;
            case "DEGRADED" -> 0.5;
            default -> 0.0;
        };
    }

    private static String bindingKey(String environment, String toolName) {
        return environment + '\u0000' + toolName;
    }

    private static String safeId(String value) {
        String text = value == null ? "" : value.trim();
        return text.matches("[A-Za-z0-9._:-]{0,128}") ? text : "invalid";
    }

    private static String tag(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return UNKNOWN;
        }
        return text.length() <= 128 ? text : text.substring(0, 128);
    }

    public record RunObservation(String environment, String runId, String sessionId,
                                 int initialEvidence, Timer.Sample timer) {
        public RunObservation {
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(timer, "timer");
            if (initialEvidence < 0) {
                throw new IllegalArgumentException("initialEvidence must not be negative");
            }
        }
    }

    private record ToolBinding(String environment, String toolName, String dataSourceId) {
    }
}
