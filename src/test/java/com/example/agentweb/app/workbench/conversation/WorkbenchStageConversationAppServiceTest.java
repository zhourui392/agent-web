package com.example.agentweb.app.workbench.conversation;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionKind;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
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
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动态 Stage Conversation 创建、可信核验、重启与幂等编排测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchStageConversationAppServiceTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-05T08:00:00Z");
    private static final Instant NOW =
            Instant.parse("2026-08-05T10:00:00Z");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final OwnerReference FOREIGN =
            OwnerReference.of("owner-2", "Other");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final String STAGE_INSTANCE_IDENTIFIER =
            "stage-implementation";

    private WorkbenchRepository workbenchRepository;
    private SessionRepository sessionRepository;
    private WorkbenchStageConversationRestartReceiptRepository receiptRepository;
    private WorkbenchStageSessionIdGenerator sessionIdGenerator;
    private WorkbenchStageConversationAppService service;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        sessionRepository = mock(SessionRepository.class);
        receiptRepository = mock(
                WorkbenchStageConversationRestartReceiptRepository.class);
        sessionIdGenerator = mock(WorkbenchStageSessionIdGenerator.class);
        service = new WorkbenchStageConversationAppService(
                workbenchRepository, sessionRepository, receiptRepository,
                sessionIdGenerator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void ensureConversationShouldCreateServerOwnedStageSessionAndPersistAggregates() {
        // Given
        Workbench workbench = newWorkbench();
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(sessionIdGenerator.nextId()).thenReturn("stage-session-0");

        // When
        WorkbenchStageConversationResult result = service.ensureConversation(
                OWNER, WORKBENCH_ID, STAGE_INSTANCE_IDENTIFIER, 0L);

        // Then
        assertTrue(result.isCreated());
        assertFalse(result.isReplayed());
        assertEquals("stage-session-0", result.getSessionId());
        assertNull(result.getPreviousSessionId());
        assertEquals(0, result.getConversationGeneration());
        assertEquals(1L, result.getWorkbenchVersion());
        ArgumentCaptor<ChatSession> session =
                ArgumentCaptor.forClass(ChatSession.class);
        InOrder persisted = inOrder(sessionRepository, workbenchRepository);
        persisted.verify(sessionRepository).addSession(session.capture());
        persisted.verify(workbenchRepository).update(workbench);
        assertEquals(SessionKind.WORKBENCH_STAGE,
                session.getValue().getSessionKind());
        assertEquals("workbench-1:stage-implementation",
                session.getValue().getContextId());
        assertEquals("owner-1", session.getValue().getUserId());
        assertEquals("Alex", session.getValue().getUserName());
        assertEquals("/workspace/agent-web",
                session.getValue().getWorkingDir());
        assertEquals("local", session.getValue().getEnv());
        assertEquals(NOW, session.getValue().getCreatedAt());
    }

    @Test
    void ensureConversationShouldVerifyExistingStageSessionWithoutWriting() {
        // Given
        Workbench workbench = newWorkbench();
        workbench.bindStageConversation(
                STAGE_INSTANCE_IDENTIFIER, "stage-session-0", OWNER,
                0L, CREATED_AT.plusSeconds(1));
        ChatSession existing = stageSession(
                "stage-session-0", CREATED_AT.plusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(sessionRepository.findById("stage-session-0"))
                .thenReturn(existing);

        // When
        WorkbenchStageConversationResult result = service.ensureConversation(
                OWNER, WORKBENCH_ID, STAGE_INSTANCE_IDENTIFIER, 1L);

        // Then
        assertFalse(result.isCreated());
        assertEquals("stage-session-0", result.getSessionId());
        verify(sessionIdGenerator, never()).nextId();
        verify(sessionRepository, never()).addSession(any(ChatSession.class));
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void restartConversationShouldRetireOldCreateNewAndPersistReceiptInOrder() {
        // Given
        Workbench workbench = restartableWorkbench();
        ChatSession oldSession = stageSession(
                "stage-session-0", CREATED_AT.plusSeconds(1));
        when(receiptRepository.findByOwnerAndIdempotencyKey(
                OWNER, "restart-key-1")).thenReturn(Optional.empty());
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(sessionRepository.findById("stage-session-0"))
                .thenReturn(oldSession);
        when(sessionIdGenerator.nextId()).thenReturn("stage-session-1");
        RestartWorkbenchStageConversationCommand command =
                new RestartWorkbenchStageConversationCommand(
                        WORKBENCH_ID, STAGE_INSTANCE_IDENTIFIER,
                        "restart-key-1", 3L);

        // When
        WorkbenchStageConversationResult result =
                service.restartConversation(OWNER, command);

        // Then
        assertTrue(result.isCreated());
        assertEquals("stage-session-1", result.getSessionId());
        assertEquals("stage-session-0", result.getPreviousSessionId());
        assertEquals(1, result.getConversationGeneration());
        assertEquals(4L, result.getWorkbenchVersion());
        assertEquals(NOW, oldSession.getRetiredAt());
        ArgumentCaptor<ChatSession> sessions =
                ArgumentCaptor.forClass(ChatSession.class);
        ArgumentCaptor<WorkbenchStageConversationRestartReceipt> receipt =
                ArgumentCaptor.forClass(
                        WorkbenchStageConversationRestartReceipt.class);
        InOrder persisted = inOrder(
                sessionRepository, workbenchRepository, receiptRepository);
        persisted.verify(sessionRepository).saveSession(sessions.capture());
        persisted.verify(sessionRepository).addSession(sessions.capture());
        persisted.verify(workbenchRepository).update(workbench);
        persisted.verify(receiptRepository).add(receipt.capture());
        assertEquals(SessionKind.WORKBENCH_STAGE,
                sessions.getAllValues().get(1).getSessionKind());
        assertEquals(STAGE_INSTANCE_IDENTIFIER,
                receipt.getValue().getStageInstanceIdentifier());
    }

    @Test
    void restartConversationShouldReplayBeforeLoadingWorkbench() {
        // Given
        WorkbenchStageConversationRestartReceipt receipt = receipt();
        when(receiptRepository.findByOwnerAndIdempotencyKey(
                OWNER, "restart-key-1")).thenReturn(Optional.of(receipt));

        // When
        WorkbenchStageConversationResult result = service.restartConversation(
                OWNER, new RestartWorkbenchStageConversationCommand(
                        WORKBENCH_ID, STAGE_INSTANCE_IDENTIFIER,
                        "restart-key-1", 0L));

        // Then
        assertTrue(result.isReplayed());
        assertFalse(result.isCreated());
        assertEquals("stage-session-1", result.getSessionId());
        verify(workbenchRepository, never()).findById(any(WorkbenchId.class));
        verify(sessionIdGenerator, never()).nextId();
    }

    @Test
    void ensureConversationShouldObscureForeignOwner() {
        // Given
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(newWorkbench()));

        // When / Then
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.ensureConversation(
                        FOREIGN, WORKBENCH_ID,
                        STAGE_INSTANCE_IDENTIFIER, 0L));
        verify(sessionRepository, never()).addSession(any(ChatSession.class));
    }

    private Workbench restartableWorkbench() {
        Workbench workbench = newWorkbench();
        workbench.bindStageConversation(
                STAGE_INSTANCE_IDENTIFIER, "stage-session-0", OWNER,
                0L, CREATED_AT.plusSeconds(1));
        workbench.completeStage(
                STAGE_INSTANCE_IDENTIFIER, OWNER,
                1L, CREATED_AT.plusSeconds(2));
        workbench.reopenStage(
                STAGE_INSTANCE_IDENTIFIER, OWNER,
                2L, CREATED_AT.plusSeconds(3));
        return workbench;
    }

    private Workbench newWorkbench() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('1'), false)), 10);
        WorkspaceTopology topology = WorkspaceTopology.of(
                "/workspace", selection);
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Stage Conversation", "独立 Stage 会话",
                AgentType.CODEX, "local", scope,
                new WorkspaceSnapshotReference(
                        "snapshot-1", topology.getTopologyHash(), repeat('2'), 1),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_INSTANCE_IDENTIFIER, stageSnapshot())), CREATED_AT);
    }

    private WorkbenchStageSnapshot stageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft("implementation", WorkbenchStageDraftContent.create(
                        10, "开发测试", "阶段说明", "阶段规则",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, CREATED_AT.minusSeconds(2));
        WorkbenchStageDefinitionRevision revision = catalog.publishDraft(
                "implementation", catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, CREATED_AT.minusSeconds(1));
        return WorkbenchStageSnapshot.fromPublishedRevision(revision);
    }

    private ChatSession stageSession(String sessionId, Instant createdAt) {
        ChatSession session = ChatSession.createWorkbenchStage(
                sessionId, AgentType.CODEX, "/workspace/agent-web",
                "workbench-1:stage-implementation",
                "owner-1", "Alex", createdAt);
        session.setEnv("local");
        return session;
    }

    private WorkbenchStageConversationRestartReceipt receipt() {
        return WorkbenchStageConversationRestartReceipt.record(
                OWNER, "restart-key-1", WORKBENCH_ID,
                STAGE_INSTANCE_IDENTIFIER, "stage-session-0",
                "stage-session-1", 1, 4L, NOW);
    }

    private String repeat(char value) {
        return String.valueOf(value).repeat(64);
    }
}
