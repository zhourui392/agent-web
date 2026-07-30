package com.example.agentweb.config.nativeagent;

import com.anthropic.agentkit.infrastructure.tools.support.LogQueryRequest;
import com.anthropic.agentkit.infrastructure.tools.support.LogQueryResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Host assembly contract for scoped HTTP, Elasticsearch, and Loki log clients.
 *
 * @author alex
 * @since 2026-07-30
 */
class NativeDiagnosisLogBackendFactoryTest {

    private final AtomicInteger queryCalls = new AtomicInteger();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> tenant = new AtomicReference<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void httpAdapter_shouldRetryOnceAndAttachHostScope() throws Exception {
        NativeDiagnosisProperties properties = remote(
                NativeDiagnosisProperties.LogQueryAdapter.HTTP);

        NativeDiagnosisLogBackendFactory.LogBinding binding =
                new NativeDiagnosisLogBackendFactory().create("test", properties);
        LogQueryResult result = binding.client().queryResult(request());

        assertThat(queryCalls).hasValue(2);
        assertThat(result.retryCount()).isOne();
        assertThat(result.dataSourceId()).isEqualTo("test-logs");
        assertThat(result.environment()).isEqualTo("test");
        assertThat(binding.service()).isEqualTo("agent-web");
        assertThat(result.toString()).doesNotContain("sentinel-secret", baseUrl);
    }

    @Test
    void elasticsearchAdapter_shouldUseFixedHostIndexMapping() throws Exception {
        NativeDiagnosisProperties properties = remote(
                NativeDiagnosisProperties.LogQueryAdapter.ELASTICSEARCH);
        properties.getBackends().setLogQueryUrl(baseUrl);
        properties.getBackends().setLogQueryEsIndexPattern("logs-*");

        LogQueryResult result = new NativeDiagnosisLogBackendFactory()
                .create("test", properties).client().queryResult(request());

        assertThat(requestPath.get()).endsWith("/_search");
        assertThat(requestPath.get()).contains("logs-*");
        assertThat(result.content()).contains("fixture es error");
        assertThat(result.dataSourceId()).isEqualTo("test-logs");
    }

    @Test
    void lokiAdapter_shouldUseFixedTenantAndSelectorMapping() throws Exception {
        NativeDiagnosisProperties properties = remote(
                NativeDiagnosisProperties.LogQueryAdapter.LOKI);
        properties.getBackends().setLogQueryUrl(baseUrl);
        properties.getBackends().setLogQueryLokiTenantId("tenant-test");
        properties.getBackends().setLogQueryLokiBaseSelector(Map.of("cluster", "test-a"));

        LogQueryResult result = new NativeDiagnosisLogBackendFactory()
                .create("test", properties).client().queryResult(request());

        assertThat(requestPath.get()).isEqualTo("/loki/api/v1/query_range");
        assertThat(tenant.get()).isEqualTo("tenant-test");
        assertThat(result.content()).contains("fixture loki error");
        assertThat(result.dataSourceId()).isEqualTo("test-logs");
    }

    private NativeDiagnosisProperties remote(NativeDiagnosisProperties.LogQueryAdapter adapter) {
        NativeDiagnosisProperties properties = new NativeDiagnosisProperties();
        NativeDiagnosisProperties.Backends backend = properties.getBackends();
        backend.setLogQueryAdapter(adapter);
        backend.setLogQueryUrl(baseUrl + "/query");
        backend.setLogQueryHealthUrl(baseUrl + "/health");
        backend.setLogQueryDataSourceId("test-logs");
        backend.setLogQueryService("agent-web");
        backend.setLogQueryServiceAliases(Set.of("web"));
        backend.setLogQueryHeaders(Map.of("Authorization", "Bearer sentinel-secret"));
        backend.setLogQueryMaxRetries(1);
        return properties;
    }

    private LogQueryRequest request() {
        return new LogQueryRequest("trace-1", "failure", "agent-web",
                "2026-07-30T00:00:00Z", "2026-07-30T01:00:00Z", "ERROR", 10);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        tenant.set(exchange.getRequestHeaders().getFirst("X-Scope-OrgID"));
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/health")) {
            respond(exchange, 200, "text/plain", "ok");
            return;
        }
        if (path.endsWith("/_search")) {
            respond(exchange, 200, "application/json", esResponse());
            return;
        }
        if (path.equals("/loki/api/v1/query_range")) {
            respond(exchange, 200, "application/json", lokiResponse());
            return;
        }
        int call = queryCalls.incrementAndGet();
        if (call == 1) {
            respond(exchange, 429, "text/plain", "rate limited");
        } else {
            respond(exchange, 200, "application/json", httpResponse());
        }
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String httpResponse() {
        return "{\"entries\":[\"fixture http error\"],\"matched\":1,"
                + "\"truncated\":false,\"queryId\":\"query-1\"}";
    }

    private String esResponse() {
        return "{\"hits\":{\"total\":{\"value\":1},\"hits\":[{\"_source\":{"
                + "\"@timestamp\":\"2026-07-30T00:30:00Z\","
                + "\"service.name\":\"agent-web\",\"log.level\":\"ERROR\","
                + "\"message\":\"fixture es error\",\"trace.id\":\"trace-1\"}}]}}";
    }

    private String lokiResponse() {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\","
                + "\"result\":[{\"stream\":{},\"values\":[[\"1785371400000000000\","
                + "\"fixture loki error\"]]}]}}";
    }
}
