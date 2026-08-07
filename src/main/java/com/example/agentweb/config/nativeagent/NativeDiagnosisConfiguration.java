package com.example.agentweb.config.nativeagent;

import com.anthropic.agentkit.application.diagnosis.PlanGuardMode;
import com.anthropic.agentkit.domain.agent.AgentBudget;
import com.anthropic.agentkit.domain.diagnosis.DataSourceBinding;
import com.anthropic.agentkit.domain.diagnosis.DataSourceType;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalog;
import com.anthropic.agentkit.domain.diagnosis.DiagnosisResourceCatalogSnapshot;
import com.anthropic.agentkit.domain.diagnosis.EnvironmentRef;
import com.anthropic.agentkit.domain.diagnosis.ReadinessStatus;
import com.anthropic.agentkit.domain.diagnosis.ServiceRef;
import com.anthropic.agentkit.domain.permission.PermissionMode;
import com.anthropic.agentkit.infrastructure.config.AppConfig;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisBackendConfig;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisToolBackends;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisToolBackendsFactory;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisToolPolicy;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnosisToolRedactor;
import com.anthropic.agentkit.infrastructure.diagnosis.DiagnoseToolFactory;
import com.anthropic.agentkit.infrastructure.llm.LlmClientFactories;
import com.anthropic.agentkit.infrastructure.tools.governance.ToolGovernance;
import com.anthropic.agentkit.infrastructure.tools.governance.FixedWindowToolRateLimiter;
import com.anthropic.agentkit.interfaces.engine.DiagnoseEngine;
import com.anthropic.agentkit.interfaces.engine.DiagnoseEngineBuilder;
import com.anthropic.agentkit.interfaces.engine.DiagnosisMode;
import com.anthropic.agentkit.interfaces.engine.ReadinessPolicy;
import com.example.agentweb.config.EnvProperties;
import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpointRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisAgentRuntime;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisEnvironmentBinding;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisHistoryMapper;
import com.example.agentweb.infra.nativeagent.NativeDiagnosisTelemetry;
import com.example.agentweb.infra.nativeagent.NativeRunSummaryMapper;
import com.example.agentweb.infra.nativeagent.NativeRuntimeRegistration;
import com.example.agentweb.infra.runtime.profile.AgentRuntimeProfile;
import com.example.agentweb.infra.runtime.profile.AgentRuntimeProfileCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring assembly root for the AgentKit diagnosis engine.
 *
 * @author alex
 * @since 2026-07-29
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NativeDiagnosisProperties.class)
public class NativeDiagnosisConfiguration {

    private final NativeDiagnosisLogBackendFactory logBackends =
            new NativeDiagnosisLogBackendFactory();

    public DiagnoseEngine diagnoseEngine(NativeDiagnosisProperties properties,
                                         EnvProperties environments,
                                         DiagnosisResourceCatalog resourceCatalog) {
        properties.validate(environments);
        NativeDiagnosisLogBackendFactory.LogBinding logBinding =
                logBackends.create(properties.getBoundEnvironment(), properties);
        return buildEngine(properties, resourceCatalog,
                toolBackends(properties, logBinding), DiagnoseToolFactory.safeDefaults());
    }

    private DiagnoseEngine buildEngine(NativeDiagnosisProperties properties,
                                       DiagnosisResourceCatalog resourceCatalog,
                                       DiagnosisToolBackends backends,
                                       ToolGovernance governance) {
        return buildEngine(properties, resourceCatalog, backends, governance,
                properties.getApiKey(), properties.getModel(), properties.getBaseUrl());
    }

    private DiagnoseEngine buildEngine(NativeDiagnosisProperties properties,
                                       DiagnosisResourceCatalog resourceCatalog,
                                       DiagnosisToolBackends backends,
                                       ToolGovernance governance,
                                       String apiKey,
                                       String model,
                                       String baseUrl) {
        DiagnoseEngineBuilder builder = DiagnoseEngineBuilder.create()
                .llm(LlmClientFactories.create(llmConfig(properties, apiKey, model, baseUrl)))
                .toolPolicy(toolPolicy(properties))
                .toolBackends(backends, governance)
                .budget(budget(properties))
                .mode(DiagnosisMode.OPERATIONAL)
                .planGuardMode(PlanGuardMode.ENFORCE)
                .readinessPolicy(ReadinessPolicy.degradedStartup())
                .resourceCatalog(resourceCatalog)
                .structuredDiagnosis();
        addOptionalKnowledge(builder, properties);
        return builder.build();
    }

    public DiagnosisResourceCatalog nativeDiagnosisResourceCatalog(
            NativeDiagnosisProperties properties) {
        NativeDiagnosisLogBackendFactory.LogBinding binding =
                logBackends.create(properties.getBoundEnvironment(), properties);
        return resourceCatalog(properties.getBoundEnvironment(), binding);
    }

    private DiagnosisResourceCatalog resourceCatalog(
            String environment, NativeDiagnosisLogBackendFactory.LogBinding logBinding) {
        if (!logBinding.configured()) {
            return DiagnosisResourceCatalog.empty();
        }
        ServiceRef service = new ServiceRef(
                logBinding.service(), logBinding.serviceAliases());
        DataSourceBinding binding = new DataSourceBinding(
                EnvironmentRef.named(environment), service,
                logBinding.dataSourceId(), DataSourceType.LOG, "LogQuery",
                logBinding.readiness(), true, Set.of("query"),
                Map.of("kind", logBinding.kind()));
        DiagnosisResourceCatalogSnapshot snapshot = new DiagnosisResourceCatalogSnapshot(
                1L, List.of(service), List.of(binding));
        return () -> snapshot;
    }

    @Bean
    @ConditionalOnProperty(name = "agent.native.enabled", havingValue = "true")
    public NativeDiagnosisHistoryMapper nativeDiagnosisHistoryMapper(
            StreamOutputExtractor outputExtractor) {
        return new NativeDiagnosisHistoryMapper(outputExtractor);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.native.enabled", havingValue = "true")
    public NativeRunSummaryMapper nativeRunSummaryMapper() {
        return new NativeRunSummaryMapper();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.native.enabled", havingValue = "true")
    public NativeDiagnosisTelemetry nativeDiagnosisTelemetry(MeterRegistry registry) {
        return new NativeDiagnosisTelemetry(registry);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agent.native.enabled", havingValue = "true")
    public NativeDiagnosisAgentRuntime nativeDiagnosisAgentRuntime(
            NativeDiagnosisProperties properties, EnvProperties environments,
            DiagnosisCheckpointRepository checkpointRepository,
            NativeDiagnosisHistoryMapper historyMapper, NativeRunSummaryMapper summaryMapper,
            NativeDiagnosisTelemetry telemetry,
            ObjectProvider<AgentRuntimeProfileCatalog> profileCatalogProvider) {
        AgentRuntimeProfileCatalog profileCatalog = profileCatalogProvider.getIfAvailable(
                () -> new AgentRuntimeProfileCatalog(List.of()));
        List<AgentRuntimeProfile> nativeProfiles = profileCatalog.profiles().stream()
                .filter(profile -> profile.getAgentType() == AgentType.NATIVE
                        && profile.isEnabled())
                .toList();
        properties.validate(environments, nativeProfiles.isEmpty());
        Map<String, NativeDiagnosisEnvironmentBinding> bindings = new LinkedHashMap<>();
        Map<String, NativeDiagnosisEnvironmentBinding> profileBindings = new LinkedHashMap<>();
        Map<String, Map<String, NativeDiagnosisEnvironmentBinding>> profileModelBindings =
                new LinkedHashMap<>();
        Map<String, NativeDiagnosisProperties> environmentConfigurations =
                properties.environmentConfigurations();
        validateLegacyConnections(nativeProfiles, environmentConfigurations);
        try {
            nativeProfiles.forEach(profile -> {
                        String environment = profileEnvironment(profile, environmentConfigurations);
                        NativeDiagnosisProperties configuration =
                                environmentConfigurations.get(environment);
                        if (profile.getApiKey() == null || profile.getApiKey().isBlank()) {
                            throw new IllegalStateException(
                                    "NATIVE Runtime Profile requires api-key: "
                                            + profile.getProfileId());
                        }
                        NativeDiagnosisLogBackendFactory.LogBinding logBinding =
                                logBackends.create(environment, configuration);
                        Map<String, NativeDiagnosisEnvironmentBinding> modelBindings =
                                new LinkedHashMap<>();
                        for (String model : profile.getAllowedModels()) {
                            DiagnoseEngine engine = buildProfileEngine(
                                    profile, configuration, environment, logBinding,
                                    telemetry, model);
                            modelBindings.put(model, NativeDiagnosisEnvironmentBinding.from(
                                    engine, environment, configuration, Clock.systemUTC()));
                        }
                        profileModelBindings.put(profile.getProfileId(), modelBindings);
                        profileBindings.put(profile.getProfileId(),
                                modelBindings.get(profile.getDefaultModel()));
                    });
            for (Map.Entry<String, NativeDiagnosisProperties> entry
                    : environmentConfigurations.entrySet()) {
                String environment = entry.getKey();
                NativeDiagnosisProperties configuration = entry.getValue();
                NativeDiagnosisEnvironmentBinding profileBinding = profileBindings.values()
                        .stream().filter(binding -> binding.environment().equals(environment))
                        .findFirst().orElse(null);
                DiagnoseEngine engine;
                if (profileBinding != null) {
                    engine = profileBinding.engine();
                } else {
                    NativeDiagnosisLogBackendFactory.LogBinding logBinding =
                            logBackends.create(environment, configuration);
                    DiagnosisToolBackends backends = toolBackends(configuration, logBinding);
                    ToolGovernance governance = toolGovernance(configuration, environment, telemetry);
                    engine = buildEngine(configuration,
                            resourceCatalog(environment, logBinding), backends, governance);
                }
                bindings.put(environment, NativeDiagnosisEnvironmentBinding.from(
                        engine, environment, configuration, Clock.systemUTC()));
            }
            return new NativeDiagnosisAgentRuntime(
                    bindings, profileBindings, profileModelBindings, checkpointRepository,
                    historyMapper, summaryMapper, telemetry);
        } catch (RuntimeException failure) {
            java.util.stream.Stream<NativeDiagnosisEnvironmentBinding> profileModels =
                    profileModelBindings.values().stream()
                            .flatMap(values -> values.values().stream());
            java.util.stream.Stream.concat(
                            java.util.stream.Stream.concat(
                                    bindings.values().stream(), profileBindings.values().stream()),
                            profileModels)
                    .map(NativeDiagnosisEnvironmentBinding::engine)
                    .distinct().forEach(DiagnoseEngine::close);
            throw failure;
        }
    }

    private void validateLegacyConnections(
            List<AgentRuntimeProfile> profiles,
            Map<String, NativeDiagnosisProperties> configurations) {
        for (AgentRuntimeProfile profile : profiles) {
            String environment = profileEnvironment(profile, configurations);
            NativeDiagnosisProperties legacy = configurations.get(environment);
            if (differentWhenConfigured(legacy.getApiKey(), profile.getApiKey())
                    || differentWhenConfigured(legacy.getModel(), profile.getDefaultModel())
                    || differentWhenConfigured(legacy.getBaseUrl(), profile.getEndpoint())) {
                throw new IllegalStateException(
                        "Legacy NATIVE connection conflicts with Runtime Profile: "
                                + profile.getProfileId());
            }
        }
    }

    private boolean differentWhenConfigured(String legacy, String profile) {
        return hasText(legacy) && !legacy.trim().equals(profile == null ? null : profile.trim());
    }

    private DiagnoseEngine buildProfileEngine(AgentRuntimeProfile profile,
                                              NativeDiagnosisProperties properties,
                                              String environment,
                                              NativeDiagnosisLogBackendFactory.LogBinding logBinding,
                                              NativeDiagnosisTelemetry telemetry,
                                              String model) {
        DiagnosisToolBackends backends = toolBackends(properties, logBinding);
        ToolGovernance governance = toolGovernance(properties, environment, telemetry);
        return buildEngine(properties, resourceCatalog(environment, logBinding), backends,
                governance, profile.getApiKey(), model, profile.getEndpoint());
    }

    private ToolGovernance toolGovernance(NativeDiagnosisProperties properties,
                                          String environment,
                                          NativeDiagnosisTelemetry telemetry) {
        return new ToolGovernance(
                Duration.ofSeconds(30), new DiagnosisToolRedactor(),
                telemetry.auditSink(environment),
                new FixedWindowToolRateLimiter(
                        properties.getTools().getMaxCallsPerMinute(), Duration.ofMinutes(1)));
    }

    private String profileEnvironment(AgentRuntimeProfile profile,
                                      Map<String, NativeDiagnosisProperties> configurations) {
        String configured = profile.getRuntimeEnvironment();
        if (configured != null && !configured.isBlank()) {
            if (!configurations.containsKey(configured.trim())) {
                throw new IllegalStateException("NATIVE Profile environment is not bound: "
                        + profile.getProfileId());
            }
            return configured.trim();
        }
        if (configurations.size() == 1) {
            return configurations.keySet().iterator().next();
        }
        throw new IllegalStateException("NATIVE Profile must specify runtime-environment: "
                + profile.getProfileId());
    }

    @Bean
    @ConditionalOnProperty(name = "agent.native.enabled", havingValue = "true")
    public NativeRuntimeRegistration nativeRuntimeRegistration(
            NativeDiagnosisProperties properties, NativeDiagnosisAgentRuntime runtime) {
        return new NativeRuntimeRegistration(
                runtime.boundEnvironments(), runtime.operationalEnvironments());
    }

    private AppConfig llmConfig(NativeDiagnosisProperties properties,
                                String apiKey,
                                String model,
                                String baseUrl) {
        String normalizedBaseUrl = textOrNull(baseUrl);
        return new AppConfig(apiKey, model,
                properties.getMaxTokens(), normalizedBaseUrl, PermissionMode.DEFAULT,
                properties.getProvider());
    }

    private AgentBudget budget(NativeDiagnosisProperties properties) {
        NativeDiagnosisProperties.Budget value = properties.getBudget();
        return new AgentBudget(value.getMaxTurns(), value.getMaxToolCalls(),
                value.getMaxInputTokens(), value.getMaxOutputTokens(),
                value.getMaxOutputCharacters(), value.getMaxLlmCalls());
    }

    private DiagnosisToolPolicy toolPolicy(NativeDiagnosisProperties properties) {
        return new DiagnosisToolPolicy(
                properties.getTools().getAllowedHttpHosts(),
                properties.getTools().getAllowedDubboAddresses(),
                properties.getTools().getAllowedDubboMethods(),
                properties.getTools().getAllowedEsIndices(),
                properties.getTools().getAllowedMysqlSchemas(),
                properties.getTools().getAllowedRedisKeyPrefixes());
    }

    private DiagnosisBackendConfig backendConfig(NativeDiagnosisProperties properties) {
        NativeDiagnosisProperties.Backends value = properties.getBackends();
        return new DiagnosisBackendConfig(
                es(value), mysql(value), redis(value), null,
                properties.getTools().isHttpEnabled()
                        ? new DiagnosisBackendConfig.HttpConfig() : null,
                properties.getTools().isDubboEnabled()
                        ? new DiagnosisBackendConfig.DubboConfig() : null);
    }

    private DiagnosisToolBackends toolBackends(
            NativeDiagnosisProperties properties,
            NativeDiagnosisLogBackendFactory.LogBinding logBinding) {
        DiagnosisToolBackends configured = DiagnosisToolBackendsFactory.fromConfig(
                backendConfig(properties));
        return new DiagnosisToolBackends(logBinding.client(), configured.es(),
                configured.mysql(), configured.redis(), configured.http(), configured.dubbo());
    }

    private DiagnosisBackendConfig.EsConfig es(NativeDiagnosisProperties.Backends value) {
        return hasText(value.getEsBaseUrl())
                ? new DiagnosisBackendConfig.EsConfig(value.getEsBaseUrl()) : null;
    }

    private DiagnosisBackendConfig.MysqlConfig mysql(NativeDiagnosisProperties.Backends value) {
        return hasText(value.getMysqlJdbcUrl())
                ? new DiagnosisBackendConfig.MysqlConfig(value.getMysqlJdbcUrl(),
                value.getMysqlUser(), value.getMysqlPassword()) : null;
    }

    private DiagnosisBackendConfig.RedisConfig redis(NativeDiagnosisProperties.Backends value) {
        return hasText(value.getRedisHost())
                ? new DiagnosisBackendConfig.RedisConfig(value.getRedisHost(), value.getRedisPort(),
                value.getRedisPassword(), value.getRedisDatabase()) : null;
    }

    private void addOptionalKnowledge(DiagnoseEngineBuilder builder,
                                      NativeDiagnosisProperties properties) {
        if (hasText(properties.getPromptPacks())) {
            builder.promptPacks(Path.of(properties.getPromptPacks().trim()));
        }
        if (hasText(properties.getSkillsRoot())) {
            builder.skills(Path.of(properties.getSkillsRoot().trim()));
        }
    }

    private String textOrNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
