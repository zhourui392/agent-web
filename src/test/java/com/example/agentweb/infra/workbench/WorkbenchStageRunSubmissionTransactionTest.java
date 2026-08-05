package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.ChatRunEventHub;
import com.example.agentweb.app.chatrun.ChatRunLauncher;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.ChatRunStreamSettings;
import com.example.agentweb.app.common.AfterCommitExecutor;
import com.example.agentweb.app.workbench.run.PreparedWorkbenchStageRun;
import com.example.agentweb.app.workbench.run.SubmitWorkbenchStageRunCommand;
import com.example.agentweb.app.workbench.run.TransactionalWorkbenchRunSubmissionExecutor;
import com.example.agentweb.app.workbench.run.WorkbenchStageRunSubmissionCommitter;
import com.example.agentweb.app.workbench.run.WorkbenchStageRunSubmissionResult;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.chatrun.RepositoryChatRunActivityGuard;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageRunAttachmentSet;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchStageRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageStatus;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dynamic Stage Run 全部持久化事实与提交后启动的真实 SQLite 原子性证明。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchStageRunSubmissionTransactionTest {

    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-stage-run-transaction");
    private static final String STAGE_INSTANCE_IDENTIFIER = "stage-design";
    private static final String SESSION_IDENTIFIER = "stage-session";
    private static final Instant COMMIT_TIME = NOW.plusSeconds(20);

    @TempDir
    Path temporaryDirectory;

    private SQLiteDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private WorkbenchPersistenceFixtures.WorkspaceFixture workspace;
    private WorkbenchStageSnapshot stageSnapshot;
    private SqliteWorkbenchRepository workbenchRepository;
    private SqliteWorkspaceSnapshotRepository workspaceSnapshotRepository;
    private SqliteWorkbenchStageRunSnapshotRepository snapshotRepository;
    private SqliteWorkbenchStageRunPromptPayloadRepository promptRepository;
    private SqliteSessionRepo sessionRepository;
    private SqliteChatRunRepository runRepository;
    private ChatRunEventHub eventHub;
    private ChatRunLauncher launcher;
    private ChatRunQueryService runQueryService;
    private ChatRunStreamSettings streamSettings;
    private long initialWorkbenchVersion;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig configuration = new SQLiteConfig();
        configuration.enforceForeignKeys(true);
        dataSource = new SQLiteDataSource(configuration);
        dataSource.setUrl("jdbc:sqlite:" + temporaryDirectory.resolve(
                "workbench-stage-run-transaction.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        new SqliteInitializer(jdbcTemplate).init();
        workspace = WorkbenchPersistenceFixtures.persistWorkspace(
                jdbcTemplate, temporaryDirectory,
                "workbench-stage-run-transaction-snapshot");
        stageSnapshot = stageSnapshot();
        Workbench workbench = Workbench.create(
                WORKBENCH_ID, OWNER, "Dynamic Workbench",
                "Complete Stage", AgentType.CODEX, "local",
                workspace.scope(), workspace.snapshot().reference(),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_INSTANCE_IDENTIFIER, stageSnapshot)), NOW);
        workbench.bindStageConversation(
                STAGE_INSTANCE_IDENTIFIER, SESSION_IDENTIFIER,
                OWNER, 0L, NOW.plusSeconds(1));
        initialWorkbenchVersion = workbench.getVersion();
        workbenchRepository = new SqliteWorkbenchRepository(jdbcTemplate);
        workbenchRepository.add(workbench);

        sessionRepository = new SqliteSessionRepo(
                jdbcTemplate,
                new CurrentUserProvider(() -> Optional.empty()));
        ChatSession session = ChatSession.createWorkbenchStage(
                SESSION_IDENTIFIER, AgentType.CODEX,
                workspace.scope().primaryRepository().getRepositoryRoot(),
                contextIdentifier(), OWNER.getOwnerId(), OWNER.getOwnerName(),
                NOW.plusSeconds(1));
        session.setEnv("local");
        sessionRepository.saveSession(session);

        workspaceSnapshotRepository =
                new SqliteWorkspaceSnapshotRepository(jdbcTemplate);
        snapshotRepository =
                new SqliteWorkbenchStageRunSnapshotRepository(jdbcTemplate);
        promptRepository =
                new SqliteWorkbenchStageRunPromptPayloadRepository(
                        jdbcTemplate);
        runRepository = new SqliteChatRunRepository(jdbcTemplate);
        eventHub = mock(ChatRunEventHub.class);
        launcher = mock(ChatRunLauncher.class);
        runQueryService = mock(ChatRunQueryService.class);
        streamSettings = mock(ChatRunStreamSettings.class);
        when(runQueryService.countActiveRuns()).thenReturn(0L);
        when(streamSettings.getMaxActiveRuns()).thenReturn(8);
    }

    @Test
    void should_CommitEveryDynamicFactBeforePublishingAndLaunching() {
        // Given
        PreparedWorkbenchStageRun prepared = preparedRun("stage-run-success");

        // When
        WorkbenchStageRunSubmissionResult result = committer(
                promptRepository).commit(OWNER, prepared);

        // Then
        assertFalse(result.isReplayed());
        assertEquals(ChatRunStatus.PENDING, result.getStatus());
        assertEquals(WorkbenchStageStatus.IN_PROGRESS,
                result.getStageStatus());
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM chat_message WHERE session_id=?",
                SESSION_IDENTIFIER));
        assertEquals("WORKBENCH_STAGE", jdbcTemplate.queryForObject(
                "SELECT session_kind FROM chat_session WHERE id=?",
                String.class, SESSION_IDENTIFIER));
        assertEquals(contextIdentifier(), jdbcTemplate.queryForObject(
                "SELECT origin_reference FROM chat_run WHERE id=?",
                String.class, result.getRunId()));
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM workbench_stage_run_snapshot "
                        + "WHERE run_id=?",
                result.getRunId()));
        assertEquals(Integer.valueOf(1), count(
                "SELECT COUNT(*) FROM workbench_stage_run_prompt_payload "
                        + "WHERE run_id=?",
                result.getRunId()));
        assertEquals(result.getRunId(), jdbcTemplate.queryForObject(
                "SELECT active_run_id FROM workbench_stage "
                        + "WHERE workbench_id=? "
                        + "AND stage_instance_identifier=?",
                String.class, WORKBENCH_ID.getValue(),
                STAGE_INSTANCE_IDENTIFIER));
        InOrder afterCommitOrder = inOrder(eventHub, launcher);
        afterCommitOrder.verify(eventHub).publish(anyList());
        afterCommitOrder.verify(launcher).launch(
                ChatRunId.of(result.getRunId()));
    }

    @Test
    void should_RollBackEveryFactAndNotLaunchWhenPromptInsertConflicts() {
        // Given
        PreparedWorkbenchStageRun prepared = preparedRun("stage-run-failure");

        // When / Then
        assertThrows(IllegalStateException.class,
                () -> committer(insertThenConflictPromptRepository())
                        .commit(OWNER, prepared));
        String runIdentifier = prepared.getSnapshot().getRunId();
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM chat_message"));
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM chat_run WHERE id=?", runIdentifier));
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM chat_run_event WHERE run_id=?",
                runIdentifier));
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM workspace_snapshot WHERE snapshot_id=?",
                prepared.getWorkspaceSnapshot().getSnapshotId()));
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM workbench_stage_run_snapshot "
                        + "WHERE run_id=?",
                runIdentifier));
        assertEquals(Integer.valueOf(0), count(
                "SELECT COUNT(*) FROM workbench_stage_run_prompt_payload "
                        + "WHERE run_id=?",
                runIdentifier));
        assertEquals(Long.valueOf(initialWorkbenchVersion),
                jdbcTemplate.queryForObject(
                        "SELECT version FROM workbench WHERE id=?",
                        Long.class, WORKBENCH_ID.getValue()));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT active_run_id FROM workbench_stage "
                        + "WHERE workbench_id=? "
                        + "AND stage_instance_identifier=?",
                String.class, WORKBENCH_ID.getValue(),
                STAGE_INSTANCE_IDENTIFIER));
        verifyNoInteractions(eventHub, launcher);
    }

    private WorkbenchStageRunSubmissionCommitter committer(
            WorkbenchStageRunPromptPayloadRepository prompts) {
        ChatRunEventAppender eventAppender = new ChatRunEventAppender(
                runRepository, new SqliteChatRunEventStore(jdbcTemplate),
                eventHub, new AfterCommitExecutor());
        return new WorkbenchStageRunSubmissionCommitter(
                workbenchRepository, workspaceSnapshotRepository,
                snapshotRepository, prompts,
                new SqliteWorkbenchStageUploadedConversationAttachmentRepository(
                        jdbcTemplate),
                UploadedAttachmentPolicy.standard(
                        10 * 1024 * 1024L, 16,
                        Duration.ofHours(24), Duration.ofHours(2)),
                sessionRepository, runRepository, eventAppender, launcher,
                new RepositoryChatRunActivityGuard(runRepository),
                runQueryService, streamSettings,
                new TransactionalWorkbenchRunSubmissionExecutor(
                        new DataSourceTransactionManager(dataSource)),
                Clock.fixed(COMMIT_TIME, ZoneOffset.UTC));
    }

    private PreparedWorkbenchStageRun preparedRun(String runIdentifier) {
        SubmitWorkbenchStageRunCommand command =
                new SubmitWorkbenchStageRunCommand(
                        WORKBENCH_ID, STAGE_INSTANCE_IDENTIFIER,
                        initialWorkbenchVersion,
                        "submission-" + runIdentifier,
                        "完成动态阶段", RunMode.DISCUSS_READ_ONLY,
                        Collections.emptyList());
        Instant snapshotTime = NOW.plusSeconds(15);
        WorkspaceSnapshot runStartSnapshot = WorkspaceSnapshot.capture(
                "run-start-" + runIdentifier,
                SnapshotPurpose.of("WORKBENCH_RUN_START"),
                workspace.snapshot().getTopology(),
                workspace.snapshot().getRepositories(),
                Collections.emptyList(),
                snapshotTime.minusMillis(1), snapshotTime);
        WorkbenchRunPromptPayload prompt = WorkbenchRunPromptPayload.freeze(
                runIdentifier, "规则\n\n" + command.getMessage(),
                WorkbenchPromptHistoryDelivery.PROMPT_PREFIX,
                snapshotTime);
        ResolvedCapabilityBinding capability =
                ResolvedCapabilityBinding.resolve(
                        "stage-policy@1", "solution-design", "1",
                        stageSnapshot.getSnapshotHash(),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(),
                        "m0-2026-07-22");
        WorkbenchStageRunSnapshot snapshot =
                WorkbenchStageRunSnapshot.create(
                        runIdentifier, WORKBENCH_ID,
                        STAGE_INSTANCE_IDENTIFIER, stageSnapshot,
                        command.getIdempotencyKey(),
                        command.getRequestHash(), command.getRunMode(),
                        workspace.scope(), runStartSnapshot.reference(),
                        capability, null, 0L,
                        WorkbenchPersistenceFixtures.HASH_A,
                        Collections.emptyList(),
                        Collections.singletonList(PromptPartSnapshot.of(
                                "USER_INPUT", "owner",
                                WorkbenchPersistenceFixtures.HASH_B,
                                command.getMessage().length())),
                        prompt.getPromptHash(),
                        RuntimeEnforcementSnapshot.readOnly(
                                "CODEX", "0.42.0",
                                workspace.scope().getScopeHash(),
                                workspace.scope().getPrimaryRepositoryKey(),
                                1800L, 8_388_608L),
                        Collections.emptyList(), Collections.emptyList(),
                        snapshotTime);
        return PreparedWorkbenchStageRun.of(
                command, snapshot, runStartSnapshot, prompt,
                VerifiedWorkbenchStageRunAttachmentSet.empty());
    }

    private WorkbenchStageRunPromptPayloadRepository
            insertThenConflictPromptRepository() {
        return new WorkbenchStageRunPromptPayloadRepository() {
            @Override
            public void add(WorkbenchRunPromptPayload payload) {
                promptRepository.add(payload);
                promptRepository.add(payload);
            }

            @Override
            public Optional<WorkbenchRunPromptPayload> findByRunId(
                    String runId) {
                return promptRepository.findByRunId(runId);
            }
        };
    }

    private WorkbenchStageSnapshot stageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(
                "solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "形成完整方案", "保持领域边界清晰",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(2));
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        "solution-design", catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList()),
                        administrator, NOW.minusSeconds(1)));
    }

    private String contextIdentifier() {
        return WORKBENCH_ID.getValue() + ":" + STAGE_INSTANCE_IDENTIFIER;
    }

    private Integer count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }
}
