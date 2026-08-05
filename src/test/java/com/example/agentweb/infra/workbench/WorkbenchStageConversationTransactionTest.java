package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.conversation.RestartWorkbenchStageConversationCommand;
import com.example.agentweb.app.workbench.conversation.WorkbenchStageConversationAppService;
import com.example.agentweb.app.workbench.conversation.WorkbenchStageConversationResult;
import com.example.agentweb.app.workbench.conversation.WorkbenchStageSessionIdGenerator;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionKind;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationRestartReceipt;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationRestartReceiptRepository;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageStatus;
import com.example.agentweb.infra.SqliteSessionRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态 Stage Conversation 的 Session、Workbench 与 Receipt 事务证明。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchStageConversationTransactionTest {

    private static final String IMPLEMENTATION_STAGE = "stage-implementation";

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private WorkbenchPersistenceFixtures.WorkspaceFixture workspace;
    private SqliteWorkbenchRepository workbenchRepository;
    private SqliteSessionRepo sessionRepository;
    private SqliteWorkbenchStageConversationRestartReceiptRepository
            receiptRepository;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("stage-conversation-transaction.db"));
        dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("test DataSource is required");
        }
        workspace = WorkbenchPersistenceFixtures.persistWorkspace(
                jdbc, tempDir, "stage-conversation-transaction-snapshot");
        workbenchRepository = new SqliteWorkbenchRepository(jdbc);
        sessionRepository = new SqliteSessionRepo(
                jdbc, new CurrentUserProvider(() -> Optional.empty()));
        receiptRepository =
                new SqliteWorkbenchStageConversationRestartReceiptRepository(
                        jdbc);
    }

    @Test
    void ensureShouldCommitStageSessionHistoryAndWorkbenchVersionTogether() {
        Workbench source = dynamicWorkbench("workbench-stage-ensure");
        workbenchRepository.add(source);
        WorkbenchStageConversationAppService service = transactionalService(
                workbenchRepository, receiptRepository, "stage-session-0");

        WorkbenchStageConversationResult result = service.ensureConversation(
                OWNER, source.getId(), IMPLEMENTATION_STAGE, 0L);

        assertTrue(result.isCreated());
        assertEquals("stage-session-0", result.getSessionId());
        assertEquals(0, result.getConversationGeneration());
        assertEquals(1L, result.getWorkbenchVersion());
        ChatSession session = sessionRepository.findById("stage-session-0");
        assertEquals(SessionKind.WORKBENCH_STAGE, session.getSessionKind());
        assertEquals(source.getId().getValue() + ":" + IMPLEMENTATION_STAGE,
                session.getContextId());
        Workbench restored = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(1L, restored.getVersion());
        assertEquals("stage-session-0", restored.stage(IMPLEMENTATION_STAGE)
                .currentConversation().getConversationId());
        assertEquals(1, restored.stage(IMPLEMENTATION_STAGE)
                .getConversationHistory().size());
    }

    @Test
    void ensureOptimisticConflictShouldRollbackInsertedStageSession() {
        Workbench source = dynamicWorkbench("workbench-stage-ensure-conflict");
        workbenchRepository.add(source);
        Workbench stale = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        Workbench winner = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        winner.bindStageConversation(
                "stage-requirement", "winner-session", OWNER,
                NOW.plusSeconds(1));
        workbenchRepository.update(winner);
        WorkbenchStageConversationAppService service = transactionalService(
                staleWorkbenchRepository(stale), receiptRepository,
                "orphan-stage-session");

        assertThrows(WorkbenchDomainException.class,
                () -> service.ensureConversation(
                        OWNER, source.getId(), IMPLEMENTATION_STAGE, 0L));

        assertNull(sessionRepository.findById("orphan-stage-session"));
        Workbench persisted = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(1L, persisted.getVersion());
        assertNull(persisted.stage(IMPLEMENTATION_STAGE)
                .currentConversation());
    }

    @Test
    void restartShouldCommitRetirementNewSessionHistoryAndReceiptTogether() {
        Workbench source = inProgressWorkbench("workbench-stage-restart");
        workbenchRepository.add(source);
        sessionRepository.addSession(stageSession(
                source, "stage-session-0", NOW.plusSeconds(1)));
        WorkbenchStageConversationAppService service = transactionalService(
                workbenchRepository, receiptRepository, "stage-session-1");

        WorkbenchStageConversationResult result = service.restartConversation(
                OWNER, new RestartWorkbenchStageConversationCommand(
                        source.getId(), IMPLEMENTATION_STAGE,
                        "restart-stage-key", 3L));

        assertFalse(result.isReplayed());
        assertEquals("stage-session-0", result.getPreviousSessionId());
        assertEquals("stage-session-1", result.getSessionId());
        assertEquals(1, result.getConversationGeneration());
        assertEquals(4L, result.getWorkbenchVersion());
        assertEquals(NOW.plusSeconds(10), sessionRepository
                .findById("stage-session-0").getRetiredAt());
        assertEquals(SessionKind.WORKBENCH_STAGE, sessionRepository
                .findById("stage-session-1").getSessionKind());
        Workbench restored = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(4L, restored.getVersion());
        assertEquals("stage-session-1", restored.stage(IMPLEMENTATION_STAGE)
                .currentConversation().getConversationId());
        assertEquals(2, restored.stage(IMPLEMENTATION_STAGE)
                .getConversationHistory().size());
        assertTrue(receiptRepository.findByOwnerAndIdempotencyKey(
                OWNER, "restart-stage-key").isPresent());
    }

    @Test
    void restartReceiptFailureShouldRollbackEveryDynamicStageMutation() {
        Workbench source = inProgressWorkbench(
                "workbench-stage-restart-rollback");
        workbenchRepository.add(source);
        sessionRepository.addSession(stageSession(
                source, "stage-session-0", NOW.plusSeconds(1)));
        WorkbenchStageConversationAppService service = transactionalService(
                workbenchRepository, addThenFailReceiptRepository(),
                "stage-session-1");

        assertThrows(IllegalStateException.class,
                () -> service.restartConversation(
                        OWNER, new RestartWorkbenchStageConversationCommand(
                                source.getId(), IMPLEMENTATION_STAGE,
                                "restart-stage-key", 3L)));

        assertNull(sessionRepository.findById("stage-session-0")
                .getRetiredAt());
        assertNull(sessionRepository.findById("stage-session-1"));
        Workbench restored = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(3L, restored.getVersion());
        assertEquals("stage-session-0", restored.stage(IMPLEMENTATION_STAGE)
                .currentConversation().getConversationId());
        assertEquals(1, restored.stage(IMPLEMENTATION_STAGE)
                .getConversationHistory().size());
        assertFalse(receiptRepository.findByOwnerAndIdempotencyKey(
                OWNER, "restart-stage-key").isPresent());
    }

    private WorkbenchStageConversationAppService transactionalService(
            WorkbenchRepository workbenches,
            WorkbenchStageConversationRestartReceiptRepository receipts,
            String generatedSessionIdentifier) {
        WorkbenchStageSessionIdGenerator generator =
                () -> generatedSessionIdentifier;
        WorkbenchStageConversationAppService target =
                new WorkbenchStageConversationAppService(
                        workbenches, sessionRepository, receipts, generator,
                        Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
        TransactionInterceptor interceptor = new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (WorkbenchStageConversationAppService) proxyFactory.getProxy();
    }

    private WorkbenchRepository staleWorkbenchRepository(Workbench stale) {
        return new WorkbenchRepository() {
            @Override
            public void add(Workbench workbench) {
                workbenchRepository.add(workbench);
            }

            @Override
            public Optional<Workbench> findById(WorkbenchId workbenchId) {
                return Optional.of(stale);
            }

            @Override
            public void update(Workbench workbench) {
                workbenchRepository.update(workbench);
            }
        };
    }

    private WorkbenchStageConversationRestartReceiptRepository
            addThenFailReceiptRepository() {
        return new WorkbenchStageConversationRestartReceiptRepository() {
            @Override
            public Optional<WorkbenchStageConversationRestartReceipt>
                    findByOwnerAndIdempotencyKey(
                            OwnerReference owner, String idempotencyKey) {
                return receiptRepository.findByOwnerAndIdempotencyKey(
                        owner, idempotencyKey);
            }

            @Override
            public void add(WorkbenchStageConversationRestartReceipt receipt) {
                receiptRepository.add(receipt);
                throw new IllegalStateException(
                        "receipt failure after insert");
            }
        };
    }

    private Workbench inProgressWorkbench(String workbenchIdentifier) {
        Workbench workbench = dynamicWorkbench(workbenchIdentifier);
        workbench.bindStageConversation(
                IMPLEMENTATION_STAGE, "stage-session-0", OWNER,
                NOW.plusSeconds(1));
        workbench.completeStage(
                IMPLEMENTATION_STAGE, OWNER, workbench.getVersion(),
                NOW.plusSeconds(2));
        workbench.reopenStage(
                IMPLEMENTATION_STAGE, OWNER, workbench.getVersion(),
                NOW.plusSeconds(3));
        assertEquals(WorkbenchStageStatus.IN_PROGRESS,
                workbench.stage(IMPLEMENTATION_STAGE).getStatus());
        return workbench;
    }

    private ChatSession stageSession(
            Workbench workbench, String sessionIdentifier,
            Instant createdAt) {
        ChatSession session = ChatSession.createWorkbenchStage(
                sessionIdentifier, workbench.getAgentType(),
                workbench.getRepositoryScope().primaryRepository()
                        .getRepositoryRoot(),
                workbench.getId().getValue() + ":" + IMPLEMENTATION_STAGE,
                OWNER.getOwnerId(), OWNER.getOwnerName(), createdAt);
        session.setEnv(workbench.getEnvironment());
        return session;
    }

    private Workbench dynamicWorkbench(String workbenchIdentifier) {
        WorkbenchStageSnapshot requirement = stageSnapshot(
                "requirement-analysis", 10, "需求分析");
        WorkbenchStageSnapshot implementation = stageSnapshot(
                "implementation", 30, "开发测试");
        return Workbench.create(
                WorkbenchId.of(workbenchIdentifier), OWNER,
                "Dynamic Workbench", "实现动态阶段",
                AgentType.CODEX, "local", workspace.scope(),
                workspace.snapshot().reference(),
                Arrays.asList(
                        WorkbenchStageState.initial(
                                IMPLEMENTATION_STAGE, implementation),
                        WorkbenchStageState.initial(
                                "stage-requirement", requirement)),
                NOW.plusMillis(30));
    }

    private WorkbenchStageSnapshot stageSnapshot(
            String definitionIdentifier, int sequenceNumber,
            String displayName) {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(
                definitionIdentifier,
                WorkbenchStageDraftContent.create(
                        sequenceNumber, displayName, "阶段说明", "阶段规则",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(2));
        WorkbenchStageDefinitionRevision revision = catalog.publishDraft(
                definitionIdentifier, catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(1));
        return WorkbenchStageSnapshot.fromPublishedRevision(revision);
    }
}
