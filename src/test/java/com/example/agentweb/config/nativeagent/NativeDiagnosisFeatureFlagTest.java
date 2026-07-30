package com.example.agentweb.config.nativeagent;

import com.anthropic.agentkit.interfaces.engine.DiagnoseEngine;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisAgentRuntime;
import com.example.agentweb.infra.nativeagent.NativeRuntimeRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * @author alex
 * @since 2026-07-29
 */
class NativeDiagnosisFeatureFlagTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NativeDiagnosisConfiguration.class);

    @Test
    void disabled_shouldNotRequireSecretsOrRegisterAnyNativeRuntimeBean() {
        runner.withPropertyValues("agent.native.enabled=false").run(context -> {
            assertFalse(context.containsBean("diagnoseEngine"));
            assertFalse(context.getBeansOfType(DiagnoseEngine.class).size() > 0);
            assertFalse(context.getBeansOfType(NativeDiagnosisAgentRuntime.class).size() > 0);
            assertFalse(context.getBeansOfType(NativeRuntimeRegistration.class).size() > 0);
        });
    }

    @Test
    void standardOpenAiProperties_shouldNotConfigureNativeCredentials()
            throws IOException {
        StandardEnvironment environment = loadApplicationConfiguration(Map.of(
                "OPENAI_API_KEY", "standard-key",
                "OPENAI_BASE_URL", "https://provider.example/v1"));

        assertEquals("", environment.getProperty("agent.native.api-key"));
        assertEquals("", environment.getProperty("agent.native.base-url"));
    }

    @Test
    void nativeSpecificProperties_shouldConfigureCredentialsIndependently() throws IOException {
        StandardEnvironment environment = loadApplicationConfiguration(Map.of(
                "OPENAI_API_KEY", "standard-key",
                "OPENAI_BASE_URL", "https://standard.example/v1",
                "AGENT_NATIVE_API_KEY", "native-key",
                "AGENT_NATIVE_BASE_URL", "https://native.example/v1"));

        assertEquals("native-key", environment.getProperty("agent.native.api-key"));
        assertEquals("https://native.example/v1",
                environment.getProperty("agent.native.base-url"));
    }

    private StandardEnvironment loadApplicationConfiguration(
            Map<String, Object> externalProperties) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("external-test-properties", externalProperties));
        new YamlPropertySourceLoader().load("application",
                        new FileSystemResource("src/main/resources/application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}
