package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchStageConversationHistory;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationProvisioning;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.infra.SqliteSessionRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Dynamic Stage 当前会话历史的真实 SQLite 绑定测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class SqliteWorkbenchStageHistoryQueryTest {

    private static final String WORKBENCH_ID = "workbench-stage-history";
    private static final String STAGE_INSTANCE_ID = "stage-design";
    private static final String SESSION_ID = "stage-session-history";
    private static final Instant CONVERSATION_CREATED_AT =
            NOW.plusSeconds(1);

    @TempDir
    Path tempDirectory;

    private JdbcTemplate jdbc;
    private SqliteSessionRepo sessionRepository;
    private WorkbenchStageConversationProvisioning provisioning;
    private SqliteWorkbenchStageHistoryQuery query;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDirectory.resolve("stage-history.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDirectory, "stage-history-snapshot");
        Workbench workbench = dynamicWorkbench(workspace);
        workbench.bindStageConversation(
                STAGE_INSTANCE_ID, SESSION_ID, OWNER,
                CONVERSATION_CREATED_AT);
        new SqliteWorkbenchRepository(jdbc).add(workbench);

        sessionRepository = new SqliteSessionRepo(
                jdbc, new CurrentUserProvider(() -> Optional.empty()));
        ChatSession session = ChatSession.createWorkbenchStage(
                SESSION_ID, AgentType.CODEX,
                workbench.getRepositoryScope().primaryRepository()
                        .getRepositoryRoot(),
                WORKBENCH_ID + ":" + STAGE_INSTANCE_ID,
                OWNER.getOwnerId(), OWNER.getOwnerName(),
                CONVERSATION_CREATED_AT);
        session.setEnv("local");
        sessionRepository.addSession(session);
        provisioning = workbench.planStageConversationEnsure(
                STAGE_INSTANCE_ID, OWNER, workbench.getVersion());
        query = new SqliteWorkbenchStageHistoryQuery(jdbc);
    }

    @Test
    void should_LoadOrderedMessagesFromExactCurrentStageSession() {
        // Given
        sessionRepository.addMessage(
                SESSION_ID, new ChatMessage(
                        "user", "Design the aggregate.",
                        NOW.plusSeconds(2)));
        sessionRepository.addMessage(
                SESSION_ID, new ChatMessage(
                        "assistant", "The snapshot is immutable.",
                        NOW.plusSeconds(3)));

        // When
        WorkbenchStageConversationHistory history =
                query.load(provisioning);

        // Then
        assertEquals(SESSION_ID, history.getSessionId());
        assertEquals(WORKBENCH_ID + ":" + STAGE_INSTANCE_ID,
                history.getContextId());
        assertEquals(0, history.getConversationGeneration());
        assertEquals("user: Design the aggregate.\n\n"
                        + "assistant: The snapshot is immutable.",
                history.getContent());
        assertEquals(WorkbenchPromptHistoryDelivery.PROMPT_PREFIX,
                history.getDelivery());
    }

    @Test
    void should_FailClosedBeforeReadingMessagesWhenSessionOwnerIsCorrupted() {
        // Given
        jdbc.update("UPDATE chat_session SET user_id = ? WHERE id = ?",
                "another-owner", SESSION_ID);

        // When
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> query.load(provisioning));

        // Then
        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                failure.getCode());
    }

    private Workbench dynamicWorkbench(
            WorkbenchPersistenceFixtures.WorkspaceFixture workspace) {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(
                "solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "形成可实施方案", "守护冻结事实",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(2));
        WorkbenchStageDefinitionRevision revision = catalog.publishDraft(
                "solution-design", catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(1));
        WorkbenchStageSnapshot snapshot =
                WorkbenchStageSnapshot.fromPublishedRevision(revision);
        return Workbench.create(
                WorkbenchId.of(WORKBENCH_ID), OWNER, "Dynamic Workbench",
                "设计动态阶段", AgentType.CODEX, "local",
                workspace.scope(), workspace.snapshot().reference(),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_INSTANCE_ID, snapshot)), NOW);
    }
}
