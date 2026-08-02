package com.example.agentweb.config.runtime;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.app.chatrun.ChatRunEventBufferFactory;
import com.example.agentweb.app.chatrun.ChatRunExecutor;
import com.example.agentweb.app.chatrun.ChatRunLauncher;
import com.example.agentweb.app.chatrun.ChatRunLifecycleService;
import com.example.agentweb.app.chatrun.ChatRunPromptBuilder;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.ChatRunRecoveryService;
import com.example.agentweb.app.chatrun.ChatRunRuntimeLauncher;
import com.example.agentweb.app.chatrun.ChatToolInvocationTrackerFactory;
import com.example.agentweb.app.chatrun.DefaultChatRunRuntimeTerminationReconciler;
import com.example.agentweb.app.chatrun.RunOriginRoutingChatRunLauncher;
import com.example.agentweb.app.runtime.ExecutionPlanProvider;
import com.example.agentweb.app.runtime.ExecutionPlanProviderRegistry;
import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.RuntimePreflightGateway;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.SkillCatalog;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import com.example.agentweb.infra.chatrun.SqliteChatRunRuntimeOutputQuery;
import com.example.agentweb.infra.runtime.AgentProcessKernel;
import com.example.agentweb.infra.runtime.EnvironmentRuntimeSecretResolver;
import com.example.agentweb.infra.runtime.RuntimeCapabilityMaterializer;
import com.example.agentweb.infra.runtime.RuntimeSecretResolver;
import com.example.agentweb.infra.runtime.SqliteChatRunRuntimeHandleStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 公共 Runtime feature flag、唯一 Launcher 与恢复依赖的生产装配测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class CommonRuntimeConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            RuntimeTestDependencies.class,
                            CommonRuntimeConfiguration.class);

    @Test
    void disabledByDefaultShouldRouteOrdinaryChatToLegacyAndNeverExposeWorkbenchLegacyFallback() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatRunLauncher.class))
                    .isInstanceOf(RunOriginRoutingChatRunLauncher.class);
            assertThat(context).hasSingleBean(ChatRunExecutor.class);
            assertThat(context).hasSingleBean(ChatRunRuntimeLauncher.class);
            assertThat(context).hasSingleBean(
                    ExecutionPlanProviderRegistry.class);
            assertThat(context).hasSingleBean(AgentExecutionGateway.class);
            assertThat(context.getBean(AgentExecutionGateway.class))
                    .isInstanceOf(AgentProcessKernel.class);
            assertThat(context).hasSingleBean(RuntimePreflightGateway.class);
            assertThat(context).hasSingleBean(RuntimeCapabilityMaterializer.class);
            assertThat(context).hasSingleBean(RuntimeSecretResolver.class);
            assertThat(context).hasSingleBean(ChatRunRecoveryService.class);
            assertThat(context).hasSingleBean(
                    DefaultChatRunRuntimeTerminationReconciler.class);
            assertThat(context).hasSingleBean(
                    SqliteChatRunRuntimeOutputQuery.class);
            assertThat(context).hasSingleBean(
                    SqliteChatRunRuntimeHandleStore.class);
        });
    }

    @Test
    void enabledWorkbenchRuntimeShouldKeepOrdinaryChatLegacyAndRegisterOneProviderPerOrigin() {
        contextRunner.withPropertyValues(
                        "agent.runtime.workbench-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatRunLauncher.class))
                            .isInstanceOf(
                                    RunOriginRoutingChatRunLauncher.class);
                    assertThat(context).hasSingleBean(ChatRunExecutor.class);
                    assertThat(context).hasSingleBean(
                            ChatRunRuntimeLauncher.class);
                    assertThat(context.getBean(
                            CommonRuntimeProperties.class)
                            .isChatEnabled()).isFalse();
                    assertThat(context.getBean(
                            CommonRuntimeProperties.class)
                            .isWorkbenchEnabled()).isTrue();
                    assertThat(context).hasSingleBean(
                            ExecutionPlanProviderRegistry.class);
                    Map<String, ExecutionPlanProvider> providers =
                            context.getBeansOfType(ExecutionPlanProvider.class);
                    assertThat(providers).hasSize(RunOrigin.values().length);
                    for (RunOrigin origin : RunOrigin.values()) {
                        assertThat(providers.values().stream()
                                .filter(provider -> provider.supports(origin))
                                .count()).isEqualTo(1L);
                    }
                    assertThat(context).hasSingleBean(
                            AgentExecutionGateway.class);
                    assertThat(context).hasSingleBean(
                            RuntimePreflightGateway.class);
                    assertThat(context).hasSingleBean(
                            RuntimeCapabilityMaterializer.class);
                    assertThat(context).hasSingleBean(
                            ChatRunRecoveryService.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            ChatRunExecutor.class,
            ChatRunRecoveryService.class,
            DefaultChatRunRuntimeTerminationReconciler.class,
            EnvironmentRuntimeSecretResolver.class,
            SqliteChatRunRuntimeOutputQuery.class,
            SqliteChatRunRuntimeHandleStore.class
    })
    static class RuntimeTestDependencies {

        @Bean(name = "agentExecutor")
        Executor agentExecutor() {
            return Runnable::run;
        }

        @Bean
        ChatRunQueryService chatRunQueryService() {
            return mock(ChatRunQueryService.class);
        }

        @Bean
        ChatRunLifecycleService chatRunLifecycleService() {
            return mock(ChatRunLifecycleService.class);
        }

        @Bean
        AgentGateway agentGateway() {
            return mock(AgentGateway.class);
        }

        @Bean
        SessionRepository sessionRepository() {
            return mock(SessionRepository.class);
        }

        @Bean
        ChatRunPromptBuilder chatRunPromptBuilder() {
            return mock(ChatRunPromptBuilder.class);
        }

        @Bean
        ChatRunEventBufferFactory chatRunEventBufferFactory() {
            return mock(ChatRunEventBufferFactory.class);
        }

        @Bean
        ChatToolInvocationTrackerFactory chatToolInvocationTrackerFactory() {
            return mock(ChatToolInvocationTrackerFactory.class);
        }

        @Bean
        ChatRunRepository chatRunRepository() {
            return mock(ChatRunRepository.class);
        }

        @Bean
        WorkbenchRunSnapshotRepository workbenchRunSnapshotRepository() {
            return mock(WorkbenchRunSnapshotRepository.class);
        }

        @Bean
        WorkbenchRunPromptPayloadRepository workbenchRunPromptPayloadRepository() {
            return mock(WorkbenchRunPromptPayloadRepository.class);
        }

        @Bean
        WorkbenchRepository workbenchRepository() {
            return mock(WorkbenchRepository.class);
        }

        @Bean
        SkillCatalog skillCatalog() {
            return Collections::emptyList;
        }

        @Bean
        McpServerCatalog mcpServerCatalog() {
            return Collections::emptyList;
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
