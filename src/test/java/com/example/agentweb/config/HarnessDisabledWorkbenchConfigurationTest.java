package com.example.agentweb.config;

import com.example.agentweb.app.chatrun.ChatRunExecutor;
import com.example.agentweb.app.chatrun.ChatRunLifecycleService;
import com.example.agentweb.app.chatrun.ChatRunPromptBuilder;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.ChatRunRuntimeTerminationReconciler;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimePreflightGateway;
import com.example.agentweb.app.workbench.WorkbenchCreationAppService;
import com.example.agentweb.app.workbench.WorkbenchLifecycleAppService;
import com.example.agentweb.app.workbench.WorkbenchReleasePolicy;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityOverrideResolver;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityOwnerService;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
import com.example.agentweb.app.workbench.run.WorkbenchRunAppService;
import com.example.agentweb.app.workbench.run.WorkbenchRunAvailability;
import com.example.agentweb.app.workbench.run.WorkbenchRunHistoryAppService;
import com.example.agentweb.app.workbench.run.WorkbenchRunPreparationSettings;
import com.example.agentweb.config.capability.CapabilityCatalogProperties;
import com.example.agentweb.config.runtime.CommonRuntimeConfiguration;
import com.example.agentweb.config.runtime.CommonRuntimeProperties;
import com.example.agentweb.config.workbench.WorkbenchCapabilityConfiguration;
import com.example.agentweb.config.workbench.WorkbenchCapabilityProperties;
import com.example.agentweb.config.workbench.WorkbenchOperationConfiguration;
import com.example.agentweb.config.workbench.WorkbenchReleaseConfiguration;
import com.example.agentweb.config.workbench.WorkbenchReleaseProperties;
import com.example.agentweb.config.workbench.WorkbenchRunConfiguration;
import com.example.agentweb.config.workbench.WorkbenchRunProperties;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.RuleCatalog;
import com.example.agentweb.domain.capability.SkillCatalog;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.harness.SkillSelectionPolicy;
import com.example.agentweb.domain.workbench.HighImpactOperationPolicy;
import com.example.agentweb.domain.workbench.PhaseCapabilityBindingResolver;
import com.example.agentweb.domain.workbench.PhaseCapabilityPreviewResolver;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileCatalog;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import com.example.agentweb.infra.capability.FileSystemMcpServerCatalog;
import com.example.agentweb.infra.capability.FileSystemPhaseCapabilityProfileCatalog;
import com.example.agentweb.infra.capability.FileSystemRuleCatalog;
import com.example.agentweb.infra.capability.FileSystemSkillCatalog;
import com.example.agentweb.infra.harness.CodexHarnessRuntimeGateway;
import com.example.agentweb.infra.harness.FileSystemRuntimeEvidenceStore;
import com.example.agentweb.infra.harness.SqliteRuntimeExecutionRepository;
import com.example.agentweb.infra.runtime.AgentProcessKernel;
import com.example.agentweb.infra.runtime.RuntimeCapabilityMaterializer;
import com.example.agentweb.interfaces.HarnessExecutionController;
import com.example.agentweb.interfaces.workbench.WorkbenchCapabilityController;
import com.example.agentweb.interfaces.workbench.WorkbenchController;
import com.example.agentweb.interfaces.workbench.WorkbenchRunController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Harness 关闭时公共 Runtime、Capability Catalog 与 Workbench 装配证据。
 *
 * @author alex
 * @since 2026-08-01
 */
class HarnessDisabledWorkbenchConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withPropertyValues("agent.harness.enabled=false")
                    .withUserConfiguration(DisabledHarnessComponents.class);

    @Test
    void disabledHarnessShouldKeepPublicRuntimeCatalogAndWorkbenchAvailable() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context).hasSingleBean(CommonRuntimeProperties.class);
            assertThat(context).hasSingleBean(AgentProcessKernel.class);
            assertThat(context).hasSingleBean(RuntimePreflightGateway.class);
            assertThat(context).hasSingleBean(
                    RuntimeCapabilityMaterializer.class);

            assertThat(context).hasSingleBean(RuleCatalog.class);
            assertThat(context.getBean(RuleCatalog.class))
                    .isInstanceOf(FileSystemRuleCatalog.class);
            assertThat(context).hasSingleBean(SkillCatalog.class);
            assertThat(context.getBean(SkillCatalog.class))
                    .isInstanceOf(FileSystemSkillCatalog.class);
            assertThat(context).hasSingleBean(McpServerCatalog.class);
            assertThat(context.getBean(McpServerCatalog.class))
                    .isInstanceOf(FileSystemMcpServerCatalog.class);
            assertThat(context).hasSingleBean(
                    PhaseCapabilityProfileCatalog.class);
            assertThat(context.getBean(PhaseCapabilityProfileCatalog.class))
                    .isInstanceOf(
                            FileSystemPhaseCapabilityProfileCatalog.class);

            assertThat(context).hasSingleBean(WorkbenchReleasePolicy.class);
            assertThat(context).hasSingleBean(WorkbenchRunAvailability.class);
            assertThat(context).hasSingleBean(
                    WorkbenchRunPreparationSettings.class);
            assertThat(context).hasSingleBean(
                    PhaseCapabilityOverrideResolver.class);
            assertThat(context).hasSingleBean(
                    PhaseCapabilityBindingResolver.class);
            assertThat(context).hasSingleBean(
                    PhaseCapabilityPreviewResolver.class);
            assertThat(context).hasSingleBean(
                    HighImpactOperationPolicy.class);
            assertThat(context).hasSingleBean(WorkbenchController.class);
            assertThat(context).hasSingleBean(WorkbenchRunController.class);
            assertThat(context).hasSingleBean(
                    WorkbenchCapabilityController.class);

            assertThat(context).doesNotHaveBean(
                    CodexHarnessRuntimeGateway.class);
            assertThat(context).doesNotHaveBean(
                    FileSystemRuntimeEvidenceStore.class);
            assertThat(context).doesNotHaveBean(
                    SqliteRuntimeExecutionRepository.class);
            assertThat(context).doesNotHaveBean(
                    HarnessExecutionController.class);
            assertThat(context).doesNotHaveBean(SkillSelectionPolicy.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            CapabilityCatalogProperties.class,
            WorkbenchCapabilityProperties.class,
            WorkbenchReleaseProperties.class,
            WorkbenchRunProperties.class
    })
    @Import({
            CommonRuntimeConfiguration.class,
            FileSystemRuleCatalog.class,
            FileSystemSkillCatalog.class,
            FileSystemMcpServerCatalog.class,
            FileSystemPhaseCapabilityProfileCatalog.class,
            WorkbenchCapabilityConfiguration.class,
            WorkbenchReleaseConfiguration.class,
            WorkbenchRunConfiguration.class,
            WorkbenchOperationConfiguration.class,
            WorkbenchController.class,
            WorkbenchRunController.class,
            WorkbenchCapabilityController.class,
            HarnessCapabilityConfig.class,
            CodexHarnessRuntimeGateway.class,
            FileSystemRuntimeEvidenceStore.class,
            SqliteRuntimeExecutionRepository.class,
            HarnessExecutionController.class
    })
    static class DisabledHarnessComponents {

        @Bean(name = "agentExecutor")
        Executor agentExecutor() {
            return Runnable::run;
        }

        @Bean
        ChatRunQueryService chatRunQueryService() {
            return mock(ChatRunQueryService.class);
        }

        @Bean
        ChatRunPromptBuilder chatRunPromptBuilder() {
            return mock(ChatRunPromptBuilder.class);
        }

        @Bean
        ChatRunRepository chatRunRepository() {
            return mock(ChatRunRepository.class);
        }

        @Bean
        ChatRunExecutor chatRunExecutor() {
            return mock(ChatRunExecutor.class);
        }

        @Bean
        ChatRunRuntimeHandleStore chatRunRuntimeHandleStore() {
            return mock(ChatRunRuntimeHandleStore.class);
        }

        @Bean
        ChatRunLifecycleService chatRunLifecycleService() {
            return mock(ChatRunLifecycleService.class);
        }

        @Bean
        ChatRunRuntimeTerminationReconciler terminationReconciler() {
            return mock(ChatRunRuntimeTerminationReconciler.class);
        }

        @Bean
        WorkbenchRunSnapshotRepository workbenchRunSnapshotRepository() {
            return mock(WorkbenchRunSnapshotRepository.class);
        }

        @Bean
        WorkbenchRunPromptPayloadRepository promptPayloadRepository() {
            return mock(WorkbenchRunPromptPayloadRepository.class);
        }

        @Bean
        WorkbenchRepository workbenchRepository() {
            return mock(WorkbenchRepository.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        WorkbenchCreationAppService workbenchCreationAppService() {
            return mock(WorkbenchCreationAppService.class);
        }

        @Bean
        WorkbenchQueryService workbenchQueryService() {
            return mock(WorkbenchQueryService.class);
        }

        @Bean
        WorkbenchLifecycleAppService workbenchLifecycleAppService() {
            return mock(WorkbenchLifecycleAppService.class);
        }

        @Bean
        WorkbenchRunAppService workbenchRunAppService() {
            return mock(WorkbenchRunAppService.class);
        }

        @Bean
        WorkbenchRunHistoryAppService workbenchRunHistoryAppService() {
            return mock(WorkbenchRunHistoryAppService.class);
        }

        @Bean
        PhaseCapabilityOwnerService phaseCapabilityOwnerService() {
            return mock(PhaseCapabilityOwnerService.class);
        }

        @Bean
        CurrentUserProvider currentUserProvider() {
            return mock(CurrentUserProvider.class);
        }
    }
}
