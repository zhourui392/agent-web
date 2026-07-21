package com.example.agentweb.infra.delivery;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.adapter.NotificationGateway;
import com.example.agentweb.adapter.OwnerNotice;
import com.example.agentweb.adapter.UserDirectory;
import com.example.agentweb.adapter.delivery.ScmCredentialStore;
import com.example.agentweb.adapter.delivery.ScmGateway;
import com.example.agentweb.adapter.requirement.RequirementDocFetcher;
import com.example.agentweb.adapter.verification.VerificationArtifactCollector;
import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.app.delivery.DeliveryAppService;
import com.example.agentweb.app.delivery.MergeRequestStore;
import com.example.agentweb.app.delivery.ScmWebhookAppService;
import com.example.agentweb.app.delivery.WebhookDedupStore;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.agentrun.RunRecallPolicyFactory;
import com.example.agentweb.app.knowledge.KnowledgeHarvestService;
import com.example.agentweb.app.knowledge.KnowledgeInboxAppService;
import com.example.agentweb.app.requirement.ExternalIntakeService;
import com.example.agentweb.app.requirement.FixRunService;
import com.example.agentweb.app.requirement.ImplementRunService;
import com.example.agentweb.app.requirement.PlanRunService;
import com.example.agentweb.app.requirement.RequirementAppService;
import com.example.agentweb.app.requirement.RequirementIdempotencyStore;
import com.example.agentweb.app.requirement.RequirementProperties;
import com.example.agentweb.app.requirement.RequirementQueryService;
import com.example.agentweb.app.requirement.RequirementRunLauncher;
import com.example.agentweb.app.requirement.RequirementRunTracker;
import com.example.agentweb.app.requirement.RunEventBus;
import com.example.agentweb.app.requirement.VerifyRunService;
import com.example.agentweb.app.verification.ArtifactStore;
import com.example.agentweb.app.workspace.PortLeaseStore;
import com.example.agentweb.app.workspace.WorkspaceAppService;
import com.example.agentweb.domain.git.UserGitConfigRepository;
import com.example.agentweb.domain.issuelog.IssueLogRepository;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.verification.VerificationRoundRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import com.example.agentweb.infra.git.GitCredentialCipher;
import com.example.agentweb.infra.knowledge.SqliteKnowledgeSuggestionRepo;
import com.example.agentweb.infra.verification.SqliteVerificationRoundRepo;
import com.example.agentweb.infra.provider.gitlab.GitLabScmGateway;
import com.example.agentweb.infra.provider.shimo.ShimoDocFetcher;
import com.example.agentweb.infra.setting.AppSettingRepository;
import com.example.agentweb.infra.verification.FlowstateArtifactCollector;
import com.example.agentweb.infra.verification.SqliteArtifactStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 交付/验证/接入（M2/M2.5）装配，随需求线总开关启停。app 服务在此以 @Bean 组装
 * （不用 @Service 注解）以便按开关整组不装配、且构造参数含配置值。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class DeliveryInfraConfig {

    // ---- infra 实现 ----

    @Bean
    public SqliteDeliveryStore sqliteDeliveryStore(JdbcTemplate jdbc) {
        return new SqliteDeliveryStore(jdbc);
    }

    @Bean
    public ScmCredentialStore scmCredentialStore(UserGitConfigRepository userGitConfigRepo,
                                                 GitCredentialCipher cipher,
                                                 AppSettingRepository appSettings,
                                                 RequirementProperties properties) {
        return new GitLabCredentialStore(userGitConfigRepo, cipher, appSettings,
                properties.getDelivery().getDefaultAccountUsername());
    }

    @Bean
    public ScmGateway scmGateway(ScmCredentialStore credentialStore, RequirementProperties properties) {
        return new GitLabScmGateway(new RestTemplate(),
                properties.getDelivery().getGitlabBaseUrl(), credentialStore,
                properties.getDelivery().getIssueIntakeLabel());
    }

    @Bean
    public VerificationArtifactCollector verificationArtifactCollector() {
        return new FlowstateArtifactCollector(64 * 1024L);
    }

    @Bean
    public ArtifactStore artifactStore(JdbcTemplate jdbc) {
        return new SqliteArtifactStore(jdbc);
    }

    @Bean
    public RequirementDocFetcher shimoDocFetcher(AgentGateway agentGateway,
                                                 RequirementProperties properties) {
        return new ShimoDocFetcher(agentGateway, runAgentType(properties),
                properties.getPlan().getWorkingDir());
    }

    /** 飞书已摘除：用户目录恒放行（校验闸失效、解析恒 empty，不卡主流程）。 */
    @Bean
    public UserDirectory userDirectory() {
        return new PermissiveUserDirectory();
    }

    /** 飞书已摘除：属主通知恒 no-op（只留日志痕迹）。 */
    @Bean
    public NotificationGateway notificationGateway() {
        return new NoopNotificationGateway();
    }

    // ---- run 执行底座 ----

    @Bean
    public RequirementRunTracker requirementRunTracker() {
        return new RequirementRunTracker();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService requirementRunExecutor() {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newCachedThreadPool(
                r -> new Thread(r, "req-run-" + counter.incrementAndGet()));
    }

    @Bean
    public RequirementRunLauncher requirementRunLauncher(AgentGateway agentGateway,
                                                         RunEventBus eventBus,
                                                         StreamOutputExtractor outputExtractor,
                                                         RequirementProperties properties,
                                                         RequirementRunTracker tracker,
                                                         ExecutorService requirementRunExecutor) {
        return new RequirementRunLauncher(agentGateway, eventBus, outputExtractor,
                properties, tracker, requirementRunExecutor);
    }

    // ---- app 编排 ----

    @Bean
    public PlanRunService planRunService(RequirementRepository repository,
                                         RequirementAppService appService,
                                         List<RequirementDocFetcher> docFetchers,
                                         PromptAssemblyService promptAssemblyService,
                                         RequirementRunLauncher launcher,
                                         RequirementProperties properties,
                                         RunRecallPolicyFactory recallPolicyFactory) {
        return new PlanRunService(repository, appService, docFetchers,
                promptAssemblyService, launcher, properties, recallPolicyFactory);
    }

    @Bean
    public ImplementRunService implementRunService(RequirementRepository repository,
                                                   RequirementAppService appService,
                                                   WorkspaceAppService workspaceAppService,
                                                   WorkspaceRepository workspaceRepository,
                                                   PortLeaseStore portLeaseStore,
                                                   PromptAssemblyService promptAssemblyService,
                                                   RequirementRunLauncher launcher,
                                                   RequirementProperties properties,
                                                   RunRecallPolicyFactory recallPolicyFactory) {
        return new ImplementRunService(repository, appService, workspaceAppService,
                workspaceRepository, portLeaseStore, promptAssemblyService, launcher, properties,
                recallPolicyFactory);
    }

    @Bean
    public FixRunService fixRunService(RequirementRepository repository,
                                       RequirementAppService appService,
                                       RequirementQueryService queryService,
                                       WorkspaceRepository workspaceRepository,
                                       PortLeaseStore portLeaseStore,
                                       PromptAssemblyService promptAssemblyService,
                                       RequirementRunLauncher launcher,
                                       RequirementProperties properties,
                                       RunRecallPolicyFactory recallPolicyFactory) {
        return new FixRunService(repository, appService, queryService, workspaceRepository,
                portLeaseStore, promptAssemblyService, launcher, properties, recallPolicyFactory);
    }

    @Bean
    public VerifyRunService verifyRunService(RequirementAppService appService,
                                             WorkspaceRepository workspaceRepository,
                                             PortLeaseStore portLeaseStore,
                                             PromptAssemblyService promptAssemblyService,
                                             RequirementRunLauncher launcher,
                                             VerificationArtifactCollector artifactCollector,
                                             ArtifactStore artifactStore,
                                             RequirementProperties properties,
                                             VerificationRoundRepository verificationRoundRepository) {
        return new VerifyRunService(appService, workspaceRepository, portLeaseStore,
                promptAssemblyService, launcher, artifactCollector, artifactStore,
                properties, verificationRoundRepository);
    }

    @Bean
    public VerificationRoundRepository verificationRoundRepository(JdbcTemplate jdbc) {
        return new SqliteVerificationRoundRepo(jdbc);
    }

    @Bean
    public DeliveryAppService deliveryAppService(RequirementRepository requirementRepository,
                                                 WorkspaceRepository workspaceRepository,
                                                 ScmCredentialStore credentialStore,
                                                 ScmGateway scmGateway,
                                                 MergeRequestStore mergeRequestStore,
                                                 RequirementProperties properties) {
        return new DeliveryAppService(requirementRepository, workspaceRepository,
                credentialStore, scmGateway, mergeRequestStore, properties);
    }

    @Bean
    public ScmWebhookAppService scmWebhookAppService(ScmGateway scmGateway,
                                                     WebhookDedupStore dedupStore,
                                                     RequirementRepository requirementRepository,
                                                     RequirementAppService requirementAppService,
                                                     RequirementIdempotencyStore idempotencyStore,
                                                     RequirementProperties properties,
                                                     UserDirectory userDirectory) {
        return new ScmWebhookAppService(scmGateway, dedupStore, requirementRepository,
                requirementAppService, idempotencyStore, properties, userDirectory);
    }

    @Bean
    public SqliteKnowledgeSuggestionRepo knowledgeSuggestionStore(JdbcTemplate jdbc) {
        return new SqliteKnowledgeSuggestionRepo(jdbc);
    }

    @Bean
    public KnowledgeHarvestService knowledgeHarvestService(SqliteKnowledgeSuggestionRepo suggestionStore,
                                                           RequirementRepository requirementRepository) {
        return new KnowledgeHarvestService(suggestionStore, requirementRepository);
    }

    @Bean
    public KnowledgeInboxAppService knowledgeInboxAppService(SqliteKnowledgeSuggestionRepo suggestionStore,
                                                             WorkspaceRepository workspaceRepository,
                                                             IssueLogRepository issueLogRepository) {
        return new KnowledgeInboxAppService(suggestionStore, workspaceRepository, issueLogRepository);
    }

    @Bean
    public ExternalIntakeService externalIntakeService(RequirementAppService appService,
                                                       RequirementIdempotencyStore idempotencyStore,
                                                       RequirementProperties properties,
                                                       UserDirectory userDirectory) {
        return new ExternalIntakeService(appService, idempotencyStore, properties, userDirectory);
    }

    private AgentType runAgentType(RequirementProperties properties) {
        return AgentType.valueOf(properties.getRunAgentType().trim().toUpperCase(Locale.ROOT));
    }

    /** 目录降级实现：通讯录未装配时校验放行、解析恒 empty（软闸失效不卡主流程）。 */
    static class PermissiveUserDirectory implements UserDirectory {

        @Override
        public boolean containsUser(String userId) {
            return true;
        }

        @Override
        public java.util.Optional<String> imUserIdOf(String userId) {
            return java.util.Optional.empty();
        }
    }

    /** 通知降级实现：飞书摘除后属主通知只留日志痕迹。 */
    @Slf4j
    static class NoopNotificationGateway implements NotificationGateway {

        @Override
        public void notifyOwner(String userId, OwnerNotice notice) {
            log.warn("notify-owner-noop userId={} title={}", userId, notice.getTitle());
        }
    }
}
