package com.example.agentweb.config.nativeagent;

import com.anthropic.agentkit.interfaces.engine.DiagnoseEngine;
import com.anthropic.agentkit.interfaces.engine.DiagnosisMode;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalog;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalogSnapshot;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.config.EnvProperties;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpointRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisAgentRuntime;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisHistoryMapper;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisTelemetry;
import com.example.agentweb.infra.nativeagent.NativeRunSummaryMapper;
import com.example.agentweb.infra.nativeagent.NativeRuntimeRegistration;
import com.example.agentweb.infra.runtime.profile.AgentRuntimeProfile;
import com.example.agentweb.infra.runtime.profile.AgentRuntimeProfileCatalog;
import com.example.agentweb.app.runtime.port.AgentRuntimeSurface;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentKit assembly root smoke test without provider calls.
 *
 * @author alex
 * @since 2026-07-29
 */
class NativeDiagnosisConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void diagnoseEngine_validConfig_shouldBuildAndClose() {
        NativeDiagnosisProperties properties = localLogsProperties();
        NativeDiagnosisConfiguration configuration = new NativeDiagnosisConfiguration();
        DiagnosisResourceCatalog resources = configuration
                .nativeDiagnosisResourceCatalog(properties);

        DiagnoseEngine engine = configuration
                .diagnoseEngine(properties, envProperties(), resources);

        assertNotNull(engine);
        assertEquals(DiagnosisMode.OPERATIONAL, engine.readiness().mode());
        assertEquals(ReadinessStatus.READY, engine.readiness().status());
        assertEquals("LogQuery", engine.readiness().capabilities().getFirst().toolName());
        engine.close();
    }

    @Test
    void localResourceCatalog_shouldExposeOnlyLogicalSecretFreeBinding() {
        NativeDiagnosisProperties properties = localLogsProperties();

        DiagnosisResourceCatalogSnapshot snapshot = new NativeDiagnosisConfiguration()
                .nativeDiagnosisResourceCatalog(properties).snapshot();

        assertEquals(1L, snapshot.generation());
        assertEquals("agent-web", snapshot.services().getFirst().name());
        assertEquals(Set.of("web"), snapshot.services().getFirst().aliases());
        assertEquals("local-agent-web-logs", snapshot.bindings().getFirst().dataSourceId());
        assertEquals("LogQuery", snapshot.bindings().getFirst().toolName());
        org.junit.jupiter.api.Assertions.assertFalse(
                snapshot.toString().contains(tempDir.toString()));
        org.junit.jupiter.api.Assertions.assertFalse(
                snapshot.toString().toLowerCase().contains("test-key"));
    }

    @Test
    void runtimeRegistration_shouldRequireTheActualNativeRuntimeBean() {
        NativeDiagnosisProperties properties = new NativeDiagnosisProperties();
        properties.setBoundEnvironment("test");
        NativeDiagnosisAgentRuntime runtime = mock(NativeDiagnosisAgentRuntime.class);
        when(runtime.boundEnvironments()).thenReturn(Set.of("test"));

        NativeRuntimeRegistration registration = new NativeDiagnosisConfiguration()
                .nativeRuntimeRegistration(properties, runtime);

        assertEquals("test", registration.boundEnvironment());
    }

    @Test
    void nativeProfile_shouldRejectConflictingLegacyConnection() {
        NativeDiagnosisProperties properties = localLogsProperties();
        properties.setBaseUrl("https://legacy.example/v1");
        AgentRuntimeProfile profile = new AgentRuntimeProfile(
                "native-profile", AgentType.NATIVE, "https://profile.example/v1",
                "profile-key", "profile-model", Set.of("profile-model"),
                "high", Set.of("high"), "test", Set.of(AgentRuntimeSurface.CHAT),
                Set.of(RunMode.DISCUSS_READ_ONLY), true);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("runtimeProfileCatalog", new AgentRuntimeProfileCatalog(List.of(profile)));
        NativeDiagnosisConfiguration configuration = new NativeDiagnosisConfiguration();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        try {
            NativeDiagnosisAgentRuntime runtime = configuration.nativeDiagnosisAgentRuntime(
                    properties, envProperties(), mock(DiagnosisCheckpointRepository.class),
                    new NativeDiagnosisHistoryMapper(new StreamOutputExtractor()),
                    new NativeRunSummaryMapper(), new NativeDiagnosisTelemetry(meterRegistry),
                    beans.getBeanProvider(AgentRuntimeProfileCatalog.class));
            runtime.close();
            fail("conflicting legacy NATIVE connection should be rejected");
        } catch (IllegalStateException failure) {
            assertTrue(failure.getMessage().contains("conflicts with Runtime Profile"));
            assertFalse(failure.getMessage().contains("profile-key"));
            assertFalse(failure.getMessage().contains("test-key"));
        } finally {
            meterRegistry.close();
        }
    }

    private NativeDiagnosisProperties localLogsProperties() {
        NativeDiagnosisProperties properties = new NativeDiagnosisProperties();
        properties.setEnabled(true);
        properties.setBoundEnvironment("test");
        properties.setModel("test-model");
        properties.setApiKey("test-key");
        properties.getLocalLogs().setEnabled(true);
        properties.getLocalLogs().setRoot(tempDir.toAbsolutePath().toString());
        properties.getLocalLogs().setService("agent-web");
        properties.getLocalLogs().setServiceAliases(Set.of("web"));
        properties.getLocalLogs().setDataSourceId("local-agent-web-logs");
        properties.getLocalLogs().setAllowedGlobs(Set.of("*.log"));
        return properties;
    }

    private EnvProperties envProperties() {
        EnvProperties envProperties = new EnvProperties();
        EnvProperties.EnvEntry entry = new EnvProperties.EnvEntry();
        entry.setKey("test");
        envProperties.setEnvs(Collections.singletonList(entry));
        return envProperties;
    }
}
