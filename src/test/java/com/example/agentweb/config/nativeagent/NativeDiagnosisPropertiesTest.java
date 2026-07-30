package com.example.agentweb.config.nativeagent;

import com.example.agentweb.config.EnvProperties;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * NATIVE 配置 fail-fast 与禁用兼容测试。
 *
 * @author alex
 * @since 2026-07-29
 */
class NativeDiagnosisPropertiesTest {

    @TempDir
    Path tempDir;

    @Test
    void disabled_shouldNotRequireSecrets() {
        NativeDiagnosisProperties properties = new NativeDiagnosisProperties();

        assertDoesNotThrow(() -> properties.validate(envs("test")));
    }

    @Test
    void enabledWithoutModelOrApiKey_shouldFailFast() {
        NativeDiagnosisProperties properties = enabled();

        assertThrows(IllegalStateException.class, () -> properties.validate(envs("test")));
    }

    @Test
    void enabledWithCompleteCoreConfig_shouldValidate() {
        NativeDiagnosisProperties properties = enabled();
        properties.setModel("test-model");
        properties.setApiKey("secret");

        assertDoesNotThrow(() -> properties.validate(envs("test")));
    }

    @Test
    void invalidEnvironmentTimezone_shouldFailFast() {
        NativeDiagnosisProperties properties = complete();
        properties.setTimezone("Mars/Olympus");

        assertThrows(IllegalStateException.class,
                () -> properties.validate(envs("test")));
    }

    @Test
    void boundEnvironmentMissingFromPublicEnvironments_shouldFailFast() {
        NativeDiagnosisProperties properties = enabled();
        properties.setModel("test-model");
        properties.setApiKey("secret");
        properties.setBoundEnvironment("prod");

        assertThrows(IllegalStateException.class, () -> properties.validate(envs("test")));
    }

    @Test
    void enabledHttpWithoutAllowlist_shouldFailClosed() {
        NativeDiagnosisProperties properties = enabled();
        properties.setModel("test-model");
        properties.setApiKey("secret");
        properties.getTools().setHttpEnabled(true);

        assertThrows(IllegalStateException.class, () -> properties.validate(envs("test")));

        properties.getTools().setAllowedHttpHosts(Collections.singleton("service.test"));
        assertDoesNotThrow(() -> properties.validate(envs("test")));
    }

    @Test
    void enabledDubbo_shouldRequireAddressAndMethodScopes() {
        NativeDiagnosisProperties properties = complete();
        properties.getTools().setDubboEnabled(true);
        properties.getTools().setAllowedDubboMethods(Set.of("getOrder"));

        assertThrows(IllegalStateException.class,
                () -> properties.validate(envs("test")));

        properties.getTools().setAllowedDubboAddresses(Set.of("dubbo.test:20880"));
        assertDoesNotThrow(() -> properties.validate(envs("test")));
    }

    @Test
    void configuredGenericBackends_shouldRequireTheirOwnScopes() {
        NativeDiagnosisProperties es = complete();
        es.getBackends().setEsBaseUrl("https://es.test");
        assertThrows(IllegalStateException.class, () -> es.validate(envs("test")));
        es.getTools().setAllowedEsIndices(Set.of("logs-*"));
        assertDoesNotThrow(() -> es.validate(envs("test")));

        NativeDiagnosisProperties mysql = complete();
        mysql.getBackends().setMysqlJdbcUrl("jdbc:mysql://db.test/orders");
        mysql.getBackends().setMysqlUser("reader");
        mysql.getBackends().setMysqlPassword("secret");
        assertThrows(IllegalStateException.class, () -> mysql.validate(envs("test")));
        mysql.getTools().setAllowedMysqlSchemas(Set.of("orders"));
        assertDoesNotThrow(() -> mysql.validate(envs("test")));

        NativeDiagnosisProperties redis = complete();
        redis.getBackends().setRedisHost("redis.test");
        assertThrows(IllegalStateException.class, () -> redis.validate(envs("test")));
        redis.getTools().setAllowedRedisKeyPrefixes(Set.of("orders:"));
        assertDoesNotThrow(() -> redis.validate(envs("test")));
    }

    @Test
    void remoteLogQuery_shouldRequireLogicalBindingHealthAndBoundedRetry() {
        NativeDiagnosisProperties properties = complete();
        properties.getBackends().setLogQueryUrl("https://logs.test/query");

        assertThrows(IllegalStateException.class,
                () -> properties.validate(envs("test")));

        completeRemoteLogBinding(properties);
        assertDoesNotThrow(() -> properties.validate(envs("test")));

        properties.getBackends().setLogQueryMaxRetries(2);
        assertThrows(IllegalStateException.class,
                () -> properties.validate(envs("test")));
    }

    @Test
    void productionLogAdapters_shouldRequireHostOwnedIndexOrTenant() {
        NativeDiagnosisProperties elasticsearch = complete();
        completeRemoteLogBinding(elasticsearch);
        elasticsearch.getBackends().setLogQueryAdapter(
                NativeDiagnosisProperties.LogQueryAdapter.ELASTICSEARCH);
        assertThrows(IllegalStateException.class,
                () -> elasticsearch.validate(envs("test")));
        elasticsearch.getBackends().setLogQueryEsIndexPattern("logs-*");
        assertDoesNotThrow(() -> elasticsearch.validate(envs("test")));

        NativeDiagnosisProperties loki = complete();
        completeRemoteLogBinding(loki);
        loki.getBackends().setLogQueryAdapter(
                NativeDiagnosisProperties.LogQueryAdapter.LOKI);
        assertThrows(IllegalStateException.class,
                () -> loki.validate(envs("test")));
        loki.getBackends().setLogQueryLokiTenantId("tenant-test");
        assertDoesNotThrow(() -> loki.validate(envs("test")));
    }

    @Test
    void httpAllowlist_shouldRejectLoopbackLinkLocalMetadataAndNonHostEntries() {
        for (String forbidden : Set.of("localhost", "127.0.0.1", "169.254.169.254",
                "metadata.google.internal", "https://service.test/path")) {
            NativeDiagnosisProperties properties = enabled();
            properties.setModel("test-model");
            properties.setApiKey("secret");
            properties.getTools().setHttpEnabled(true);
            properties.getTools().setAllowedHttpHosts(Collections.singleton(forbidden));

            assertThrows(IllegalStateException.class,
                    () -> properties.validate(envs("test")), forbidden);
        }
    }

    @Test
    void enabledLocalLogsWithReadableAbsoluteRoot_shouldValidate() {
        NativeDiagnosisProperties properties = complete();
        enableLocalLogs(properties, tempDir);

        assertDoesNotThrow(() -> properties.validate(envs("test")));
    }

    @Test
    void localLogs_shouldRejectRelativeMissingAndNonDirectoryRoots() throws Exception {
        for (String forbidden : Set.of("logs", tempDir.resolve("missing").toString(),
                tempDir.resolve("service.log").toString())) {
            if (forbidden.endsWith("service.log")) {
                java.nio.file.Files.writeString(Path.of(forbidden), "fixture");
            }
            NativeDiagnosisProperties properties = complete();
            enableLocalLogs(properties, tempDir);
            properties.getLocalLogs().setRoot(forbidden);

            assertThrows(IllegalStateException.class,
                    () -> properties.validate(envs("test")), forbidden);
        }
    }

    @Test
    void localLogs_shouldRejectUnsafeGlobInvalidZoneAndNonPositiveLimits() {
        NativeDiagnosisProperties unsafeGlob = completeWithLocalLogs();
        unsafeGlob.getLocalLogs().setAllowedGlobs(Set.of("../*.log"));
        assertThrows(IllegalStateException.class,
                () -> unsafeGlob.validate(envs("test")));

        NativeDiagnosisProperties invalidZone = completeWithLocalLogs();
        invalidZone.getLocalLogs().setLogZone("Mars/Olympus");
        assertThrows(IllegalStateException.class,
                () -> invalidZone.validate(envs("test")));

        NativeDiagnosisProperties invalidLimit = completeWithLocalLogs();
        invalidLimit.getLocalLogs().setMaxScanDurationMs(0L);
        assertThrows(IllegalStateException.class,
                () -> invalidLimit.validate(envs("test")));
    }

    @Test
    void localAndRemoteLogQuery_shouldFailFastInsteadOfOverridingEachOther() {
        NativeDiagnosisProperties properties = completeWithLocalLogs();
        properties.getBackends().setLogQueryUrl("https://logs.test/query");

        assertThrows(IllegalStateException.class,
                () -> properties.validate(envs("test")));
    }

    @Test
    void multipleEnvironments_shouldValidateAndRemainIndependent() {
        NativeDiagnosisProperties root = new NativeDiagnosisProperties();
        root.setEnabled(true);
        NativeDiagnosisProperties test = environment("test-model", "test-secret");
        NativeDiagnosisProperties prod = environment("prod-model", "prod-secret");
        Map<String, NativeDiagnosisProperties> configured = new LinkedHashMap<>();
        configured.put("test", test);
        configured.put("prod", prod);
        root.setEnvironments(configured);

        assertDoesNotThrow(() -> root.validate(envs("test", "prod")));
        org.junit.jupiter.api.Assertions.assertEquals(
                Set.of("test", "prod"), root.environmentConfigurations().keySet());
        org.junit.jupiter.api.Assertions.assertEquals("test-secret",
                root.environmentConfigurations().get("test").getApiKey());
        org.junit.jupiter.api.Assertions.assertEquals("prod-secret",
                root.environmentConfigurations().get("prod").getApiKey());
    }

    @Test
    void multipleEnvironments_shouldFailWhenAnyEnvironmentIsIncompleteOrNested() {
        NativeDiagnosisProperties root = new NativeDiagnosisProperties();
        root.setEnabled(true);
        NativeDiagnosisProperties test = environment("test-model", "test-secret");
        NativeDiagnosisProperties prod = environment("prod-model", "");
        root.setEnvironments(Map.of("test", test, "prod", prod));

        assertThrows(IllegalStateException.class,
                () -> root.validate(envs("test", "prod")));

        prod.setApiKey("prod-secret");
        prod.setEnvironments(Map.of("nested", environment("nested", "secret")));
        assertThrows(IllegalStateException.class,
                () -> root.validate(envs("test", "prod")));
    }

    private NativeDiagnosisProperties enabled() {
        NativeDiagnosisProperties properties = new NativeDiagnosisProperties();
        properties.setEnabled(true);
        properties.setBoundEnvironment("test");
        return properties;
    }

    private NativeDiagnosisProperties complete() {
        NativeDiagnosisProperties properties = enabled();
        properties.setModel("test-model");
        properties.setApiKey("secret");
        return properties;
    }

    private NativeDiagnosisProperties completeWithLocalLogs() {
        NativeDiagnosisProperties properties = complete();
        enableLocalLogs(properties, tempDir);
        return properties;
    }

    private NativeDiagnosisProperties environment(String model, String apiKey) {
        NativeDiagnosisProperties properties = new NativeDiagnosisProperties();
        properties.setModel(model);
        properties.setApiKey(apiKey);
        return properties;
    }

    private void enableLocalLogs(NativeDiagnosisProperties properties, Path root) {
        properties.getLocalLogs().setEnabled(true);
        properties.getLocalLogs().setRoot(root.toAbsolutePath().toString());
        properties.getLocalLogs().setService("agent-web");
        properties.getLocalLogs().setDataSourceId("local-agent-web-logs");
        properties.getLocalLogs().setAllowedGlobs(Set.of("*.log"));
    }

    private void completeRemoteLogBinding(NativeDiagnosisProperties properties) {
        properties.getBackends().setLogQueryUrl("https://logs.test/query");
        properties.getBackends().setLogQueryHealthUrl("https://logs.test/health");
        properties.getBackends().setLogQueryDataSourceId("test-logs");
        properties.getBackends().setLogQueryService("agent-web");
    }

    private EnvProperties envs(String... keys) {
        EnvProperties properties = new EnvProperties();
        properties.setEnvs(java.util.Arrays.stream(keys).map(key -> {
            EnvProperties.EnvEntry entry = new EnvProperties.EnvEntry();
            entry.setKey(key);
            return entry;
        }).toList());
        return properties;
    }
}
