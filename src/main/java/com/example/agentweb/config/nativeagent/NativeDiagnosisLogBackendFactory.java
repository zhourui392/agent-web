package com.example.agentweb.config.nativeagent;

import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.anthropic.agentkit.infrastructure.tools.support.BackendHealth;
import com.anthropic.agentkit.infrastructure.tools.support.BackendRetryPolicy;
import com.anthropic.agentkit.infrastructure.tools.support.ElasticsearchLogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.HttpBackendHealthProbe;
import com.anthropic.agentkit.infrastructure.tools.support.HttpLogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.LocalFileLogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.LocalLogSource;
import com.anthropic.agentkit.infrastructure.tools.support.LogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.LokiLogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.ResilientLogQueryClient;
import com.anthropic.agentkit.infrastructure.tools.support.ScopedLogQueryClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Builds one environment-scoped log backend from host-owned configuration.
 *
 * @author alex
 * @since 2026-07-30
 */
final class NativeDiagnosisLogBackendFactory {

    LogBinding create(String environment, NativeDiagnosisProperties properties) {
        if (properties.getLocalLogs().isEnabled()) {
            return local(environment, properties.getLocalLogs());
        }
        if (hasText(properties.getBackends().getLogQueryUrl())) {
            return remote(environment, properties.getBackends());
        }
        return LogBinding.empty();
    }

    private LogBinding local(String environment, NativeDiagnosisProperties.LocalLogs value) {
        LocalLogSource source = new LocalLogSource(
                value.getDataSourceId(), value.rootPath(), value.getAllowedGlobs(),
                value.zoneId(), value.getMaxFiles(), value.getMaxLines(), value.getMaxBytes(),
                value.getMaxDepth(), Duration.ofMillis(value.getMaxScanDurationMs()));
        LogQueryClient client = new ScopedLogQueryClient(
                new LocalFileLogQueryClient(source), value.getDataSourceId(),
                environment, value.getService());
        return new LogBinding(client, value.getDataSourceId(), value.getService(),
                value.getServiceAliases(), ReadinessStatus.READY, "local-file");
    }

    private LogBinding remote(String environment, NativeDiagnosisProperties.Backends value) {
        LogQueryClient raw = remoteClient(value);
        BackendHealth health = health(value);
        BackendRetryPolicy retry = new BackendRetryPolicy(
                value.getLogQueryMaxRetries(),
                Duration.ofMillis(value.getLogQueryRetryMaxElapsedMs()));
        LogQueryClient resilient = new ResilientLogQueryClient(raw, retry, () -> health);
        LogQueryClient scoped = new ScopedLogQueryClient(
                resilient, value.getLogQueryDataSourceId(), environment,
                value.getLogQueryService());
        return new LogBinding(scoped, value.getLogQueryDataSourceId(),
                value.getLogQueryService(), value.getLogQueryServiceAliases(),
                health.status(), value.getLogQueryAdapter().name().toLowerCase());
    }

    private LogQueryClient remoteClient(NativeDiagnosisProperties.Backends value) {
        Duration timeout = Duration.ofMillis(value.getLogQueryTimeoutMs());
        return switch (value.getLogQueryAdapter()) {
            case HTTP -> new HttpLogQueryClient(
                    value.getLogQueryUrl(), value.getLogQueryHeaders(),
                    new HttpLogQueryClient.Options(timeout,
                            value.getLogQueryMaxBodyBytes(),
                            value.isLogQueryLegacyTextAllowed()));
            case ELASTICSEARCH -> elasticsearch(value, timeout);
            case LOKI -> loki(value, timeout);
        };
    }

    private LogQueryClient elasticsearch(NativeDiagnosisProperties.Backends value,
                                         Duration timeout) {
        ElasticsearchLogQueryClient.Binding binding = new ElasticsearchLogQueryClient.Binding(
                value.getLogQueryEsIndexPattern(), value.getLogQueryEsTimestampField(),
                value.getLogQueryEsServiceField(), value.getLogQueryEsLevelField(),
                value.getLogQueryEsMessageField(), value.getLogQueryEsTraceField(),
                value.getLogQueryEsFixedTermFilters());
        ElasticsearchLogQueryClient.Options options = new ElasticsearchLogQueryClient.Options(
                timeout, value.getLogQueryMaxBodyBytes(), value.getLogQueryMaxResults());
        return new ElasticsearchLogQueryClient(
                value.getLogQueryUrl(), binding, value.getLogQueryHeaders(),
                options, safeHttpClient(timeout));
    }

    private LogQueryClient loki(NativeDiagnosisProperties.Backends value, Duration timeout) {
        LokiLogQueryClient.Binding binding = new LokiLogQueryClient.Binding(
                value.getLogQueryLokiTenantId(), value.getLogQueryLokiBaseSelector(),
                value.getLogQueryLokiServiceLabel(), value.getLogQueryLokiLevelLabel());
        LokiLogQueryClient.Options options = new LokiLogQueryClient.Options(
                timeout, value.getLogQueryMaxBodyBytes(), value.getLogQueryMaxResults());
        return new LokiLogQueryClient(
                value.getLogQueryUrl(), binding, value.getLogQueryHeaders(),
                options, safeHttpClient(timeout));
    }

    private BackendHealth health(NativeDiagnosisProperties.Backends value) {
        Duration timeout = Duration.ofMillis(value.getLogQueryTimeoutMs());
        return new HttpBackendHealthProbe(
                value.getLogQueryHealthUrl(), value.getLogQueryHeaders(), timeout,
                safeHttpClient(timeout)).probe();
    }

    private HttpClient safeHttpClient(Duration timeout) {
        return HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    record LogBinding(LogQueryClient client, String dataSourceId, String service,
                      Set<String> serviceAliases, ReadinessStatus readiness, String kind) {

        LogBinding {
            serviceAliases = serviceAliases == null ? Set.of() : Set.copyOf(serviceAliases);
        }

        static LogBinding empty() {
            return new LogBinding(null, "", "", Set.of(),
                    ReadinessStatus.UNAVAILABLE, "not-configured");
        }

        boolean configured() {
            return client != null;
        }
    }
}
