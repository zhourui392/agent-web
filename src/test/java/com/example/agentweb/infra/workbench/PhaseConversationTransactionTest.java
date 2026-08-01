package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.conversation.PhaseConversationAppService;
import com.example.agentweb.app.workbench.conversation.PhaseSessionIdGenerator;
import com.example.agentweb.app.workbench.conversation.RestartPhaseConversationCommand;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.workbench.PhaseConversationRestartReceipt;
import com.example.agentweb.domain.workbench.PhaseConversationRestartReceiptRepository;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.infra.SqliteInitializer;
import com.example.agentweb.infra.SqliteSessionRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase Conversation 的 Session、Workbench、Receipt 同事务回滚证明。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseConversationTransactionTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqliteWorkbenchRepository workbenchRepository;
    private SqliteSessionRepo sessionRepository;
    private SqlitePhaseConversationRestartReceiptRepository receiptRepository;
    private WorkbenchPersistenceFixtures.WorkspaceFixture workspace;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("phase-conversation-tx.db"));
        jdbc = new JdbcTemplate(dataSource);
        new SqliteInitializer(jdbc).init();
        workspace = WorkbenchPersistenceFixtures.persistWorkspace(
                jdbc, tempDir, "phase-conversation-tx-snapshot");
        workbenchRepository = new SqliteWorkbenchRepository(jdbc);
        sessionRepository = new SqliteSessionRepo(
                jdbc, new CurrentUserProvider(() -> Optional.empty()));
        receiptRepository = new SqlitePhaseConversationRestartReceiptRepository(jdbc);
    }

    @Test
    void restartReceiptFailureShouldRollbackOldRetirementNewSessionWorkbenchAndReceipt() {
        Workbench source = inProgressWorkbench("workbench-restart-rollback");
        workbenchRepository.add(source);
        ChatSession oldSession = phaseSession(source, "session-0", NOW.plusSeconds(1));
        sessionRepository.saveSession(oldSession);
        PhaseConversationRestartReceiptRepository failingReceiptRepository =
                addThenFailReceiptRepository();
        PhaseConversationAppService service = transactionalService(
                workbenchRepository, failingReceiptRepository, "session-1");

        assertThrows(IllegalStateException.class, () -> service.restartConversation(
                OWNER, new RestartPhaseConversationCommand(
                        source.getId(), WorkbenchPhase.IMPLEMENT_TEST,
                        "restart-key-1", 3L)));

        ChatSession restoredOld = sessionRepository.findById("session-0");
        assertNull(restoredOld.getRetiredAt());
        assertNull(sessionRepository.findById("session-1"));
        Workbench restoredWorkbench = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(3L, restoredWorkbench.getVersion());
        assertEquals("session-0", restoredWorkbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .currentConversation().getConversationId());
        assertEquals(1, restoredWorkbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getConversationHistory().size());
        assertFalse(receiptRepository.findByOwnerAndIdempotencyKey(
                OWNER, "restart-key-1").isPresent());
    }

    @Test
    void ensureOptimisticConflictShouldRollbackInsertedOrphanSession() {
        Workbench source = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-ensure-conflict");
        workbenchRepository.add(source);
        Workbench stale = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        Workbench winner = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        winner.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "winner-session",
                OWNER, NOW.plusSeconds(1));
        workbenchRepository.update(winner);
        WorkbenchRepository staleRepository = staleWorkbenchRepository(stale);
        PhaseConversationAppService service = transactionalService(
                staleRepository, receiptRepository, "orphan-session");

        assertThrows(WorkbenchDomainException.class, () -> service.ensureConversation(
                OWNER, source.getId(), WorkbenchPhase.IMPLEMENT_TEST, 0L));

        assertNull(sessionRepository.findById("orphan-session"));
        Workbench persisted = workbenchRepository.findById(source.getId())
                .orElseThrow(AssertionError::new);
        assertEquals(1L, persisted.getVersion());
        assertNull(persisted.phase(WorkbenchPhase.IMPLEMENT_TEST).currentConversation());
    }

    private PhaseConversationAppService transactionalService(
            WorkbenchRepository workbenches,
            PhaseConversationRestartReceiptRepository receipts,
            String generatedSessionId) {
        PhaseSessionIdGenerator generator = () -> generatedSessionId;
        PhaseConversationAppService target = new PhaseConversationAppService(
                workbenches, sessionRepository, receipts, generator,
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (PhaseConversationAppService) proxyFactory.getProxy();
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

    private PhaseConversationRestartReceiptRepository addThenFailReceiptRepository() {
        return new PhaseConversationRestartReceiptRepository() {
            @Override
            public Optional<PhaseConversationRestartReceipt> findByOwnerAndIdempotencyKey(
                    com.example.agentweb.domain.workbench.OwnerReference owner,
                    String idempotencyKey) {
                return receiptRepository.findByOwnerAndIdempotencyKey(owner, idempotencyKey);
            }

            @Override
            public void add(PhaseConversationRestartReceipt receipt) {
                receiptRepository.add(receipt);
                throw new IllegalStateException("receipt failure after insert");
            }
        };
    }

    private Workbench inProgressWorkbench(String workbenchId) {
        Workbench workbench = WorkbenchPersistenceFixtures.newWorkbench(workspace, workbenchId);
        workbench.bindConversation(
                WorkbenchPhase.IMPLEMENT_TEST, "session-0", OWNER, NOW.plusSeconds(1));
        workbench.prepareRun(
                WorkbenchPhase.IMPLEMENT_TEST, "run-1", RunMode.MODIFY_WORKSPACE,
                OWNER, NOW.plusSeconds(2));
        workbench.finishRun(
                WorkbenchPhase.IMPLEMENT_TEST, "run-1", NOW.plusSeconds(3));
        return workbench;
    }

    private ChatSession phaseSession(
            Workbench workbench, String sessionId, Instant createdAt) {
        ChatSession session = ChatSession.createWorkbenchPhase(
                sessionId, workbench.getAgentType(),
                workbench.getRepositoryScope().primaryRepository().getRepositoryRoot(),
                workbench.getId().getValue() + ":" + WorkbenchPhase.IMPLEMENT_TEST.name(),
                OWNER.getOwnerId(), OWNER.getOwnerName(), createdAt);
        session.setEnv(workbench.getEnvironment());
        return session;
    }
}
