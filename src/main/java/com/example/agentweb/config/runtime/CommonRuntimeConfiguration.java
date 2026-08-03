package com.example.agentweb.config.runtime;

import com.example.agentweb.app.chatrun.ChatRunLauncher;
import com.example.agentweb.app.chatrun.ChatRunExecutor;
import com.example.agentweb.app.chatrun.ChatRunLifecycleService;
import com.example.agentweb.app.chatrun.ChatRunPromptBuilder;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.ChatRunRuntimeLauncher;
import com.example.agentweb.app.chatrun.ChatRunRuntimeTerminationReconciler;
import com.example.agentweb.app.chatrun.RunOriginRoutingChatRunLauncher;
import com.example.agentweb.app.runtime.ChatExecutionPlanProvider;
import com.example.agentweb.app.runtime.ExecutionPlanProvider;
import com.example.agentweb.app.runtime.ExecutionPlanProviderRegistry;
import com.example.agentweb.app.runtime.WorkbenchExecutionPlanProvider;
import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimePreflightGateway;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.SkillCatalog;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import com.example.agentweb.infra.runtime.AgentProcessKernel;
import com.example.agentweb.infra.runtime.CodexRuntimeCompatibilityMatrix;
import com.example.agentweb.infra.runtime.CodexRuntimePreflightGateway;
import com.example.agentweb.infra.runtime.RuntimeCapabilityMaterializer;
import com.example.agentweb.infra.runtime.RuntimeCleanup;
import com.example.agentweb.infra.runtime.RuntimeCommandFactory;
import com.example.agentweb.infra.runtime.RuntimeEventDecoder;
import com.example.agentweb.infra.runtime.RuntimeOutputRedactor;
import com.example.agentweb.infra.runtime.RuntimeProcessRegistry;
import com.example.agentweb.infra.runtime.RuntimeSecretResolver;
import com.example.agentweb.infra.runtime.RuntimeWorkspaceMaterializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 公共 Runtime 基础设施、Provider Registry 与 Chat launcher 切换的生产装配。
 *
 * <p>底层 Gateway/Preflight 无条件注册，保证 recovery 与 Workbench preflight 在开关关闭时
 * 仍具备 fail-closed 技术观察；来源路由保证普通 Chat 与 Workbench 不互相旁路。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CommonRuntimeProperties.class)
public class CommonRuntimeConfiguration {

    @Bean
    public RuntimeCommandFactory commonRuntimeCommandFactory(
            CommonRuntimeProperties properties) {
        return new RuntimeCommandFactory(properties.getCodexCommand());
    }

    @Bean
    public RuntimeWorkspaceMaterializer commonRuntimeWorkspaceMaterializer(
            CommonRuntimeProperties properties,
            ObjectProvider<UploadedConversationAttachmentStorage>
                    attachmentStorageProvider) {
        UploadedConversationAttachmentStorage attachmentStorage =
                attachmentStorageProvider.getIfAvailable();
        if (attachmentStorage == null) {
            return new RuntimeWorkspaceMaterializer(
                    Paths.get(properties.getTempRoot()));
        }
        return new RuntimeWorkspaceMaterializer(
                Paths.get(properties.getTempRoot()), attachmentStorage);
    }

    @Bean
    @ConditionalOnMissingBean(RuntimeSecretResolver.class)
    public RuntimeSecretResolver commonRuntimeSecretResolver(
            Environment environment) {
        return reference -> {
            String value = environment.getProperty(reference);
            return value == null ? null : value.toCharArray();
        };
    }

    @Bean
    public RuntimeCapabilityMaterializer commonRuntimeCapabilityMaterializer(
            SkillCatalog skillCatalog,
            McpServerCatalog mcpServerCatalog,
            RuntimeSecretResolver secretResolver) {
        return new RuntimeCapabilityMaterializer(
                skillCatalog, mcpServerCatalog, secretResolver);
    }

    @Bean
    public RuntimeOutputRedactor commonRuntimeOutputRedactor() {
        return new RuntimeOutputRedactor();
    }

    @Bean
    public RuntimeEventDecoder commonRuntimeEventDecoder(
            RuntimeOutputRedactor outputRedactor) {
        return new RuntimeEventDecoder(
                outputRedactor,
                com.example.agentweb.domain.runtime.RuntimeCommandPolicy.platformDefault(),
                new com.example.agentweb.infra.cli.CodexEventNormalizer());
    }

    @Bean
    public RuntimeProcessRegistry commonRuntimeProcessRegistry() {
        return new RuntimeProcessRegistry();
    }

    @Bean
    public RuntimeCleanup commonRuntimeCleanup() {
        return new RuntimeCleanup();
    }

    @Bean(name = "commonRuntimeMonitorExecutor")
    public ThreadPoolTaskExecutor commonRuntimeMonitorExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("common-runtime-monitor-");
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "close")
    public AgentProcessKernel commonRuntimeExecutionGateway(
            RuntimeCommandFactory commandFactory,
            RuntimeWorkspaceMaterializer workspaceMaterializer,
            RuntimeCapabilityMaterializer capabilityMaterializer,
            RuntimeEventDecoder eventDecoder,
            RuntimeProcessRegistry processRegistry,
            RuntimeCleanup cleanup,
            @Qualifier("commonRuntimeMonitorExecutor")
                    ThreadPoolTaskExecutor monitorExecutor) {
        return new AgentProcessKernel(
                commandFactory, workspaceMaterializer,
                capabilityMaterializer, eventDecoder,
                processRegistry, cleanup, monitorExecutor);
    }

    @Bean
    public CodexRuntimeCompatibilityMatrix commonRuntimeCompatibilityMatrix(
            CommonRuntimeProperties properties) {
        return new CodexRuntimeCompatibilityMatrix(
                properties.getCompatibilityMatrixVersion(),
                new java.util.LinkedHashSet<SandboxMode>(
                        Arrays.asList(
                                SandboxMode.READ_ONLY,
                                SandboxMode.WORKSPACE_WRITE)),
                true);
    }

    @Bean
    public RuntimePreflightGateway commonRuntimePreflightGateway(
            CommonRuntimeProperties properties,
            CodexRuntimeCompatibilityMatrix compatibilityMatrix) {
        return new CodexRuntimePreflightGateway(
                properties.getCodexCommand(),
                compatibilityMatrix,
                Duration.ofSeconds(
                        properties.getVersionProbeTimeoutSeconds()),
                properties.getVersionProbeMaxBytes());
    }

    @Bean
    public ChatExecutionPlanProvider chatExecutionPlanProvider(
            ChatRunQueryService queryService,
            ChatRunPromptBuilder promptBuilder,
            CommonRuntimeProperties properties) {
        ResolvedCapabilityBinding binding =
                ResolvedCapabilityBinding.resolve(
                        "common-runtime-policy@1", "chat-default", "1",
                        CanonicalHashing.sha256("chat-default@1"),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(),
                        properties.getCompatibilityMatrixVersion());
        RuntimeLimits limits = new RuntimeLimits(
                Duration.ofSeconds(properties.getChatTimeoutSeconds()),
                properties.getChatMaxOutputBytes());
        return new ChatExecutionPlanProvider(
                queryService, promptBuilder, binding, limits);
    }

    @Bean
    public WorkbenchExecutionPlanProvider workbenchExecutionPlanProvider(
            WorkbenchRunSnapshotRepository snapshotRepository,
            WorkbenchRunPromptPayloadRepository promptRepository,
            WorkbenchRepository workbenchRepository) {
        return new WorkbenchExecutionPlanProvider(
                snapshotRepository, promptRepository, workbenchRepository);
    }

    @Bean
    public ExecutionPlanProviderRegistry executionPlanProviderRegistry(
            List<ExecutionPlanProvider> providers) {
        return new ExecutionPlanProviderRegistry(providers);
    }

    @Bean
    public ChatRunRuntimeLauncher commonRuntimeChatRunLauncher(
            ChatRunRepository runRepository,
            ExecutionPlanProviderRegistry registry,
            AgentExecutionGateway executionGateway,
            ChatRunRuntimeHandleStore handleStore,
            ChatRunLifecycleService lifecycleService,
            ChatRunRuntimeTerminationReconciler terminationReconciler,
            Clock clock,
            @Qualifier("agentExecutor") Executor executor) {
        return new ChatRunRuntimeLauncher(
                runRepository, registry, executionGateway, handleStore,
                lifecycleService, terminationReconciler, clock, executor);
    }

    @Bean
    @Primary
    public ChatRunLauncher runOriginRoutingChatRunLauncher(
            ChatRunRepository runRepository,
            ChatRunExecutor legacyLauncher,
            ChatRunRuntimeLauncher commonRuntimeLauncher,
            CommonRuntimeProperties properties) {
        ChatRunLauncher disabledWorkbenchLauncher = runId -> {
            throw new IllegalStateException(
                    "Workbench common Runtime is disabled");
        };
        Map<RunOrigin, ChatRunLauncher> routes =
                new EnumMap<RunOrigin, ChatRunLauncher>(RunOrigin.class);
        routes.put(RunOrigin.CHAT, properties.isChatEnabled()
                ? commonRuntimeLauncher : legacyLauncher);
        routes.put(RunOrigin.WORKBENCH, properties.isWorkbenchEnabled()
                ? commonRuntimeLauncher : disabledWorkbenchLauncher);
        return new RunOriginRoutingChatRunLauncher(runRepository, routes);
    }
}
