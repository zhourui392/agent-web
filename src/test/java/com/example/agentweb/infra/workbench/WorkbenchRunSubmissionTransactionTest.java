package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.ChatRunEventHub;
import com.example.agentweb.app.chatrun.ChatRunLauncher;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.ChatRunStreamSettings;
import com.example.agentweb.app.common.AfterCommitExecutor;
import com.example.agentweb.app.workbench.run.PreparedWorkbenchRun;
import com.example.agentweb.app.workbench.run.SubmitWorkbenchRunCommand;
import com.example.agentweb.app.workbench.run.TransactionalWorkbenchRunSubmissionExecutor;
import com.example.agentweb.app.workbench.run.WorkbenchRunSubmissionCommitter;
import com.example.agentweb.app.workbench.run.WorkbenchRunSubmissionResult;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.chatrun.RepositoryChatRunActivityGuard;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.HandoffReception;
import com.example.agentweb.domain.workbench.HandoffReceptionRepository;
import com.example.agentweb.domain.workbench.HandoffSnapshotReference;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchPhaseStatus;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotRepository;
import com.example.agentweb.infra.SqliteInitializer;
import com.example.agentweb.infra.SqliteSessionRepo;
import com.example.agentweb.infra.chatrun.SqliteChatRunEventStore;
import com.example.agentweb.infra.chatrun.SqliteChatRunRepository;
import com.example.agentweb.infra.workspace.SqliteWorkspaceSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workbench Run 六类事实与 Workbench 活动引用的真实 SQLite 原子提交证明。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunSubmissionTransactionTest {

    private static final String WORKBENCH_ID = "workbench-run-transaction";
    private static final String REQUIREMENT_SESSION_ID = "requirement-session";
    private static final String DESIGN_SESSION_ID = "design-session";
    private static final Instant COMMIT_TIME = NOW.plusSeconds(20);

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqliteWorkbenchRepository workbenchRepository;
    private SqliteWorkspaceSnapshotRepository workspaceSnapshotRepository;
    private SqliteWorkbenchRunSnapshotRepository snapshotRepository;
    private SqliteWorkbenchRunPromptPayloadRepository promptRepository;
    private SqliteHandoffReceptionRepository receptionRepository;
    private SqliteSessionRepo sessionRepository;
    private SqliteChatRunRepository runRepository;
    private ChatRunEventHub eventHub;
    private ChatRunLauncher launcher;
    private ChatRunQueryService runQueryService;
    private ChatRunStreamSettings streamSettings;
    private WorkbenchPersistenceFixtures.WorkspaceFixture workspace;
    private PhaseHandoff requirementHandoff;
    private long initialWorkbenchVersion;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:"
                + tempDir.resolve("workbench-run-transaction.db"));
        jdbc = new JdbcTemplate(dataSource);
        new SqliteInitializer(jdbc).init();
        workspace = WorkbenchPersistenceFixtures.persistWorkspace(
                jdbc, tempDir, "workbench-run-transaction-snapshot");
        workspaceSnapshotRepository =
                new SqliteWorkspaceSnapshotRepository(jdbc);

        Workbench workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, WORKBENCH_ID);
        bindConversation(workbench, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                REQUIREMENT_SESSION_ID, NOW.plusSeconds(1));
        bindConversation(workbench, WorkbenchPhase.SOLUTION_DESIGN,
                DESIGN_SESSION_ID, NOW.plusSeconds(2));
        initialWorkbenchVersion = workbench.getVersion();

        workbenchRepository = new SqliteWorkbenchRepository(jdbc);
        workbenchRepository.add(workbench);
        requirementHandoff = PhaseHandoff.create(
                workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "需求边界已确认", Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), workbench.getRepositoryScope(),
                OWNER, NOW.plusSeconds(3));
        new SqlitePhaseHandoffRepository(jdbc).add(requirementHandoff);
        sessionRepository = new SqliteSessionRepo(
                jdbc, new CurrentUserProvider(() -> Optional.empty()));
        sessionRepository.saveSession(phaseSession(
                workbench, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                REQUIREMENT_SESSION_ID, NOW.plusSeconds(1)));
        sessionRepository.saveSession(phaseSession(
                workbench, WorkbenchPhase.SOLUTION_DESIGN,
                DESIGN_SESSION_ID, NOW.plusSeconds(2)));

        snapshotRepository = new SqliteWorkbenchRunSnapshotRepository(jdbc);
        promptRepository = new SqliteWorkbenchRunPromptPayloadRepository(jdbc);
        receptionRepository = new SqliteHandoffReceptionRepository(jdbc);
        runRepository = new SqliteChatRunRepository(jdbc);
        eventHub = mock(ChatRunEventHub.class);
        launcher = mock(ChatRunLauncher.class);
        runQueryService = mock(ChatRunQueryService.class);
        streamSettings = mock(ChatRunStreamSettings.class);
        when(runQueryService.countActiveRuns()).thenReturn(0L);
        when(streamSettings.getMaxActiveRuns()).thenReturn(8);
    }

    @Test
    void successfulSubmissionShouldCommitEveryFactBeforePublishingAndLaunching() {
        PreparedWorkbenchRun prepared = preparedRun(
                "successful-run", WorkbenchPhase.REQUIREMENT_ANALYSIS);

        WorkbenchRunSubmissionResult result = committer(
                workbenchRepository, workspaceSnapshotRepository,
                snapshotRepository,
                promptRepository, receptionRepository).commit(OWNER, prepared);

        assertFalse(result.isReplayed());
        assertEquals("successful-run", result.getRunId());
        assertEquals(REQUIREMENT_SESSION_ID, result.getSessionId());
        assertEquals(ChatRunStatus.PENDING, result.getStatus());
        assertEquals(WorkbenchPhaseStatus.IN_PROGRESS, result.getPhaseStatus());
        assertEquals(initialWorkbenchVersion + 1L, result.getWorkbenchVersion());
        assertEquals(prepared.getSnapshot().getCapabilityBinding().getBindingHash(),
                result.getCapabilitySnapshotHash());
        assertEquals(prepared.getSnapshot().getRepositoryScopeHash(),
                result.getRepositoryScopeHash());

        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM chat_message WHERE session_id=? AND role='user'",
                REQUIREMENT_SESSION_ID));
        assertEquals(prepared.getCommand().getMessage(), jdbc.queryForObject(
                "SELECT content FROM chat_message WHERE session_id=?",
                String.class, REQUIREMENT_SESSION_ID));
        assertEquals("WORKBENCH", jdbc.queryForObject(
                "SELECT run_origin FROM chat_run WHERE id=?",
                String.class, prepared.getSnapshot().getRunId()));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM chat_run WHERE id=?",
                String.class, prepared.getSnapshot().getRunId()));
        assertEquals(Long.valueOf(1L), jdbc.queryForObject(
                "SELECT last_event_seq FROM chat_run WHERE id=?",
                Long.class, prepared.getSnapshot().getRunId()));
        assertEquals(WORKBENCH_ID + ":REQUIREMENT_ANALYSIS", jdbc.queryForObject(
                "SELECT origin_reference FROM chat_run WHERE id=?",
                String.class, prepared.getSnapshot().getRunId()));
        assertEquals(prepared.getSnapshot().getRunId(), jdbc.queryForObject(
                "SELECT execution_context_id FROM chat_run WHERE id=?",
                String.class, prepared.getSnapshot().getRunId()));
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM chat_run_event "
                        + "WHERE run_id=? AND seq=1 AND event_type='run_status'",
                prepared.getSnapshot().getRunId()));
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM workspace_snapshot WHERE snapshot_id=?",
                prepared.getWorkspaceSnapshot().getSnapshotId()));
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM workbench_run_snapshot WHERE run_id=?",
                prepared.getSnapshot().getRunId()));
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM workbench_run_prompt_payload WHERE run_id=?",
                prepared.getSnapshot().getRunId()));
        assertEquals(Long.valueOf(initialWorkbenchVersion + 1L), jdbc.queryForObject(
                "SELECT version FROM workbench WHERE id=?", Long.class, WORKBENCH_ID));
        assertNull(jdbc.queryForObject(
                "SELECT active_write_run_id FROM workbench WHERE id=?",
                String.class, WORKBENCH_ID));
        assertEquals(prepared.getSnapshot().getRunId(), jdbc.queryForObject(
                "SELECT active_run_id FROM workbench_phase "
                        + "WHERE workbench_id=? AND phase=?",
                String.class, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS.name()));
        assertEquals(RunMode.DISCUSS_READ_ONLY.name(), jdbc.queryForObject(
                "SELECT active_run_mode FROM workbench_phase "
                        + "WHERE workbench_id=? AND phase=?",
                String.class, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS.name()));

        InOrder afterCommitOrder = inOrder(eventHub, launcher);
        afterCommitOrder.verify(eventHub).publish(anyList());
        afterCommitOrder.verify(launcher).launch(
                ChatRunId.of(prepared.getSnapshot().getRunId()));
    }

    @Test
    void workspaceSnapshotConflictShouldRollbackEverySubmissionFactAndNotLaunch() {
        PreparedWorkbenchRun prepared = preparedRun(
                "workspace-snapshot-failure-run",
                WorkbenchPhase.REQUIREMENT_ANALYSIS);

        assertThrows(IllegalStateException.class, () -> committer(
                workbenchRepository,
                insertThenConflictWorkspaceSnapshotRepository(),
                snapshotRepository, promptRepository, receptionRepository)
                .commit(OWNER, prepared));

        assertSubmissionRolledBack(
                prepared, WorkbenchPhase.REQUIREMENT_ANALYSIS);
    }

    @Test
    void snapshotConflictAfterFirstInsertShouldRollbackEveryEarlierFactAndNotLaunch() {
        PreparedWorkbenchRun prepared = preparedRun(
                "snapshot-failure-run", WorkbenchPhase.REQUIREMENT_ANALYSIS);

        assertThrows(IllegalStateException.class, () -> committer(
                workbenchRepository, workspaceSnapshotRepository,
                insertThenConflictSnapshotRepository(),
                promptRepository, receptionRepository).commit(OWNER, prepared));

        assertSubmissionRolledBack(prepared, WorkbenchPhase.REQUIREMENT_ANALYSIS);
    }

    @Test
    void promptConflictAfterFirstInsertShouldRollbackSnapshotAndEveryEarlierFactAndNotLaunch() {
        PreparedWorkbenchRun prepared = preparedRun(
                "prompt-failure-run", WorkbenchPhase.REQUIREMENT_ANALYSIS);

        assertThrows(IllegalStateException.class, () -> committer(
                workbenchRepository, workspaceSnapshotRepository,
                snapshotRepository,
                insertThenConflictPromptRepository(), receptionRepository)
                .commit(OWNER, prepared));

        assertSubmissionRolledBack(prepared, WorkbenchPhase.REQUIREMENT_ANALYSIS);
    }

    @Test
    void handoffConflictAfterInsertShouldRollbackReceptionAndEveryRunFactAndNotLaunch() {
        PreparedWorkbenchRun prepared = preparedRun(
                "handoff-failure-run", WorkbenchPhase.SOLUTION_DESIGN);

        assertThrows(IllegalStateException.class, () -> committer(
                workbenchRepository, workspaceSnapshotRepository,
                snapshotRepository, promptRepository,
                addThenConflictReceptionRepository()).commit(OWNER, prepared));

        assertSubmissionRolledBack(prepared, WorkbenchPhase.SOLUTION_DESIGN);
    }

    @Test
    void workbenchOptimisticConflictShouldRollbackEveryRunFactAndNotLaunch() {
        PreparedWorkbenchRun prepared = preparedRun(
                "workbench-conflict-run", WorkbenchPhase.REQUIREMENT_ANALYSIS);

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class, () -> committer(
                        conflictingWorkbenchRepository(),
                        workspaceSnapshotRepository, snapshotRepository,
                        promptRepository, receptionRepository)
                        .commit(OWNER, prepared));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, failure.getCode());
        assertSubmissionRolledBack(prepared, WorkbenchPhase.REQUIREMENT_ANALYSIS);
    }

    @Test
    void exactRetryShouldReplayCommittedRunWithoutDuplicateWritesOrSecondLaunch() {
        PreparedWorkbenchRun prepared = preparedRun(
                "idempotent-run", WorkbenchPhase.REQUIREMENT_ANALYSIS);
        WorkbenchRunSubmissionCommitter target = committer(
                workbenchRepository, workspaceSnapshotRepository,
                snapshotRepository,
                promptRepository, receptionRepository);

        WorkbenchRunSubmissionResult first = target.commit(OWNER, prepared);
        WorkbenchRunSubmissionResult replay = target.commit(OWNER, prepared);

        assertFalse(first.isReplayed());
        assertTrue(replay.isReplayed());
        assertEquals(first.getRunId(), replay.getRunId());
        assertEquals(first.getSessionId(), replay.getSessionId());
        assertEquals(first.getWorkbenchVersion(), replay.getWorkbenchVersion());
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM chat_message WHERE session_id=?",
                REQUIREMENT_SESSION_ID));
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM chat_run WHERE id=?", first.getRunId()));
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM chat_run_event WHERE run_id=?", first.getRunId()));
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM workspace_snapshot WHERE snapshot_id=?",
                prepared.getWorkspaceSnapshot().getSnapshotId()));
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM workbench_run_snapshot WHERE run_id=?",
                first.getRunId()));
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM workbench_run_prompt_payload WHERE run_id=?",
                first.getRunId()));
        assertEquals(Long.valueOf(initialWorkbenchVersion + 1L), jdbc.queryForObject(
                "SELECT version FROM workbench WHERE id=?", Long.class, WORKBENCH_ID));
        verify(eventHub, times(1)).publish(anyList());
        verify(launcher, times(1)).launch(ChatRunId.of(first.getRunId()));
    }

    private WorkbenchRunSubmissionCommitter committer(
            WorkbenchRepository workbenches,
            WorkspaceSnapshotRepository workspaceSnapshots,
            WorkbenchRunSnapshotRepository snapshots,
            WorkbenchRunPromptPayloadRepository prompts,
            HandoffReceptionRepository receptions) {
        ChatRunEventAppender eventAppender = new ChatRunEventAppender(
                runRepository, new SqliteChatRunEventStore(jdbc),
                eventHub, new AfterCommitExecutor());
        return new WorkbenchRunSubmissionCommitter(
                workbenches, workspaceSnapshots, snapshots, prompts,
                receptions,
                sessionRepository, runRepository, eventAppender, launcher,
                new RepositoryChatRunActivityGuard(runRepository),
                runQueryService, streamSettings,
                new TransactionalWorkbenchRunSubmissionExecutor(
                        new DataSourceTransactionManager(dataSource)),
                Clock.fixed(COMMIT_TIME, ZoneOffset.UTC));
    }

    private PreparedWorkbenchRun preparedRun(
            String runId, WorkbenchPhase phase) {
        HandoffReception reception = handoffReception(phase);
        Long handoffVersion = reception == null
                ? null : Long.valueOf(reception.getSourceVersion());
        SubmitWorkbenchRunCommand command = new SubmitWorkbenchRunCommand(
                WorkbenchId.of(WORKBENCH_ID), phase, initialWorkbenchVersion,
                "submission-" + runId, "执行 " + phase.name(),
                RunMode.DISCUSS_READ_ONLY, handoffVersion,
                null, Collections.emptyList());
        Instant snapshotTime = NOW.plusSeconds(15);
        WorkspaceSnapshot runStartSnapshot = WorkspaceSnapshot.capture(
                "run-start-" + runId,
                SnapshotPurpose.of("WORKBENCH_RUN_START"),
                workspace.snapshot().getTopology(),
                workspace.snapshot().getRepositories(),
                Collections.emptyList(), snapshotTime.minusMillis(1),
                snapshotTime);
        WorkbenchRunPromptPayload prompt = WorkbenchRunPromptPayload.freeze(
                runId, "规则\n\n" + command.getMessage(),
                WorkbenchPromptHistoryDelivery.PROMPT_PREFIX, snapshotTime);
        HandoffSnapshotReference handoff = reception == null ? null
                : HandoffSnapshotReference.of(
                        reception.getSourcePhase(), reception.getSourceVersion(),
                        reception.getSourceHash());
        WorkbenchRunSnapshot snapshot = WorkbenchRunSnapshot.create(
                runId, command.getWorkbenchId(), phase,
                command.getIdempotencyKey(), command.getRequestHash(),
                command.getRunMode(), workspace.scope(),
                runStartSnapshot.reference(),
                WorkbenchPersistenceFixtures.capabilityBinding(), null, handoff,
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner",
                        CanonicalHashing.sha256(command.getMessage()),
                        command.getMessage().length())),
                prompt.getPromptHash(), RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42.0", workspace.scope().getScopeHash(),
                        workspace.scope().getPrimaryRepositoryKey(),
                        1800L, 8_388_608L),
                null, snapshotTime);
        return PreparedWorkbenchRun.of(
                command, snapshot, runStartSnapshot,
                prompt, null, reception);
    }

    private HandoffReception handoffReception(WorkbenchPhase phase) {
        if (phase == WorkbenchPhase.REQUIREMENT_ANALYSIS) {
            return null;
        }
        return HandoffReception.accept(
                WorkbenchId.of(WORKBENCH_ID), phase,
                phase.defaultHandoffSource().orElseThrow(AssertionError::new),
                requirementHandoff.getVersion(), requirementHandoff.getContentHash(),
                OWNER, NOW.plusSeconds(10));
    }

    private WorkspaceSnapshotRepository
            insertThenConflictWorkspaceSnapshotRepository() {
        return new WorkspaceSnapshotRepository() {
            @Override
            public void add(WorkspaceSnapshot snapshot) {
                workspaceSnapshotRepository.add(snapshot);
                workspaceSnapshotRepository.add(snapshot);
            }

            @Override
            public Optional<WorkspaceSnapshot> findById(String snapshotId) {
                return workspaceSnapshotRepository.findById(snapshotId);
            }
        };
    }

    private WorkbenchRunSnapshotRepository insertThenConflictSnapshotRepository() {
        return new WorkbenchRunSnapshotRepository() {
            @Override
            public void add(WorkbenchRunSnapshot snapshot) {
                snapshotRepository.add(snapshot);
                snapshotRepository.add(snapshot);
            }

            @Override
            public Optional<WorkbenchRunSnapshot> findByRunId(String runId) {
                return snapshotRepository.findByRunId(runId);
            }

            @Override
            public Optional<WorkbenchRunSnapshot>
                    findByWorkbenchPhaseAndIdempotencyKey(
                    WorkbenchId workbenchId, WorkbenchPhase phase,
                    String submissionIdempotencyKey) {
                return snapshotRepository.findByWorkbenchPhaseAndIdempotencyKey(
                        workbenchId, phase, submissionIdempotencyKey);
            }
        };
    }

    private WorkbenchRunPromptPayloadRepository insertThenConflictPromptRepository() {
        return new WorkbenchRunPromptPayloadRepository() {
            @Override
            public void add(WorkbenchRunPromptPayload payload) {
                promptRepository.add(payload);
                promptRepository.add(payload);
            }

            @Override
            public Optional<WorkbenchRunPromptPayload> findByRunId(String runId) {
                return promptRepository.findByRunId(runId);
            }
        };
    }

    private HandoffReceptionRepository addThenConflictReceptionRepository() {
        return new HandoffReceptionRepository() {
            @Override
            public void save(HandoffReception reception) {
                receptionRepository.save(reception);
                throw new IllegalStateException("handoff conflict after insert");
            }

            @Override
            public Optional<HandoffReception> find(
                    WorkbenchId workbenchId, WorkbenchPhase targetPhase,
                    WorkbenchPhase sourcePhase) {
                return receptionRepository.find(
                        workbenchId, targetPhase, sourcePhase);
            }
        };
    }

    private WorkbenchRepository conflictingWorkbenchRepository() {
        return new WorkbenchRepository() {
            @Override
            public void add(Workbench workbench) {
                workbenchRepository.add(workbench);
            }

            @Override
            public Optional<Workbench> findById(WorkbenchId workbenchId) {
                return workbenchRepository.findById(workbenchId);
            }

            @Override
            public void update(Workbench workbench) {
                jdbc.update("UPDATE workbench SET version=version+1 WHERE id=?",
                        workbench.getId().getValue());
                workbenchRepository.update(workbench);
            }
        };
    }

    private void assertSubmissionRolledBack(
            PreparedWorkbenchRun prepared, WorkbenchPhase phase) {
        String runId = prepared.getSnapshot().getRunId();
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM chat_message"));
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM chat_run WHERE id=?", runId));
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM chat_run_event WHERE run_id=?", runId));
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM workspace_snapshot WHERE snapshot_id=?",
                prepared.getWorkspaceSnapshot().getSnapshotId()));
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM workbench_run_snapshot WHERE run_id=?", runId));
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM workbench_run_prompt_payload WHERE run_id=?", runId));
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM workbench_handoff_reception"));
        assertEquals(Long.valueOf(initialWorkbenchVersion), jdbc.queryForObject(
                "SELECT version FROM workbench WHERE id=?", Long.class, WORKBENCH_ID));
        assertNull(jdbc.queryForObject(
                "SELECT active_write_run_id FROM workbench WHERE id=?",
                String.class, WORKBENCH_ID));
        assertNull(jdbc.queryForObject(
                "SELECT active_run_id FROM workbench_phase "
                        + "WHERE workbench_id=? AND phase=?",
                String.class, WORKBENCH_ID, phase.name()));
        verifyNoInteractions(eventHub, launcher);
    }

    private Integer count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private void bindConversation(
            Workbench workbench, WorkbenchPhase phase,
            String sessionId, Instant createdAt) {
        workbench.bindConversation(phase, sessionId, OWNER, createdAt);
    }

    private ChatSession phaseSession(
            Workbench workbench, WorkbenchPhase phase,
            String sessionId, Instant createdAt) {
        ChatSession session = ChatSession.createWorkbenchPhase(
                sessionId, workbench.getAgentType(),
                workbench.getRepositoryScope().primaryRepository().getRepositoryRoot(),
                WORKBENCH_ID + ":" + phase.name(),
                OWNER.getOwnerId(), OWNER.getOwnerName(), createdAt);
        session.setEnv(workbench.getEnvironment());
        return session;
    }
}
