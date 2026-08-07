package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.WorkbenchCreationAppService;
import com.example.agentweb.app.workbench.WorkbenchLifecycleAppService;
import com.example.agentweb.app.workbench.WorkbenchLifecycleResult;
import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.WorkbenchStageLifecycleResult;
import com.example.agentweb.app.workbench.query.WorkbenchDetailView;
import com.example.agentweb.app.workbench.query.WorkbenchListCursor;
import com.example.agentweb.app.workbench.query.WorkbenchListItemView;
import com.example.agentweb.app.workbench.query.WorkbenchListPage;
import com.example.agentweb.app.workbench.query.WorkbenchListRequest;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageStatus;
import com.example.agentweb.interfaces.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Workbench Owner 列表、详情和人工生命周期 HTTP 契约测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@WebMvcTest(WorkbenchController.class)
@Import({GlobalExceptionHandler.class, WorkbenchExceptionHandler.class})
class WorkbenchOwnerControllerTest {

    private static final String OWNER_ID = "owner-1";
    private static final String OWNER_NAME = "Alex";
    private static final String WORKBENCH_ID = "workbench-1";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private WorkbenchCreationAppService creationAppService;

    @MockBean
    private WorkbenchQueryService queryService;

    @MockBean
    private WorkbenchLifecycleAppService lifecycleAppService;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void authenticateOwner() {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(currentUserProvider.currentUserName()).thenReturn(OWNER_NAME);
    }

    @Test
    void listShouldScopeByOwnerAndConvertFilterCursorAndPage() throws Exception {
        WorkbenchListItemView item = new WorkbenchListItemView(
                WORKBENCH_ID, "Workbench MVP", "ACTIVE", "CODEX", "local",
                "agent-web", 2, "run-1", 1000L, 2000L, 7L);
        WorkbenchListPage page = new WorkbenchListPage(
                Collections.singletonList(item),
                new WorkbenchListCursor(1900L, "workbench-cursor"));
        when(queryService.listByOwner(eq(OWNER_ID), any())).thenReturn(page);

        mvc.perform(get("/api/workbenches")
                        .param("status", "active")
                        .param("cursorUpdatedAt", "2500")
                        .param("cursorWorkbenchId", "workbench-before")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(WORKBENCH_ID))
                .andExpect(jsonPath("$.items[0].title").value("Workbench MVP"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].agentType").value("CODEX"))
                .andExpect(jsonPath("$.items[0].primaryRepositoryKey")
                        .value("agent-web"))
                .andExpect(jsonPath("$.items[0].repositoryCount").value(2))
                .andExpect(jsonPath("$.items[0].activeWriteRunId").value("run-1"))
                .andExpect(jsonPath("$.items[0].version").value(7))
                .andExpect(jsonPath("$.nextCursor.updatedAt").value(1900))
                .andExpect(jsonPath("$.nextCursor.workbenchId")
                        .value("workbench-cursor"));

        ArgumentCaptor<WorkbenchListRequest> request =
                ArgumentCaptor.forClass(WorkbenchListRequest.class);
        verify(queryService).listByOwner(eq(OWNER_ID), request.capture());
        assertEquals(WorkbenchStatus.ACTIVE, request.getValue().getStatus());
        assertEquals(2, request.getValue().getLimit());
        assertNotNull(request.getValue().getCursor());
        assertEquals(2500L, request.getValue().getCursor().getUpdatedAt());
        assertEquals("workbench-before",
                request.getValue().getCursor().getWorkbenchId());
    }

    @Test
    void listWithoutFiltersShouldUseDefaultLimitAndNoCursor() throws Exception {
        when(queryService.listByOwner(eq(OWNER_ID), any())).thenReturn(
                new WorkbenchListPage(Collections.emptyList(), null));

        mvc.perform(get("/api/workbenches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        ArgumentCaptor<WorkbenchListRequest> request =
                ArgumentCaptor.forClass(WorkbenchListRequest.class);
        verify(queryService).listByOwner(eq(OWNER_ID), request.capture());
        assertNull(request.getValue().getStatus());
        assertNull(request.getValue().getCursor());
        assertEquals(20, request.getValue().getLimit());
    }

    @Test
    void partialListCursorShouldReturn400BeforeQuery() throws Exception {
        mvc.perform(get("/api/workbenches")
                        .param("cursorUpdatedAt", "2500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "cursorUpdatedAt and cursorWorkbenchId must be provided together"));

        verify(queryService, never()).listByOwner(any(), any());
    }

    @Test
    void detailShouldReturnOwnerScopedSafeProjection() throws Exception {
        when(queryService.findDetailByOwner(OWNER_ID, WORKBENCH_ID))
                .thenReturn(Optional.of(detailView()));

        mvc.perform(get("/api/workbenches/{workbenchId}", WORKBENCH_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(WORKBENCH_ID))
                .andExpect(jsonPath("$.title").value("Workbench MVP"))
                .andExpect(jsonPath("$.originalGoal").value("实现本地开发工作台"))
                .andExpect(jsonPath("$.repositoryScope.scopeHash").value("scope-hash"))
                .andExpect(jsonPath("$.repositoryScope.primaryRepositoryKey")
                        .value("agent-web"))
                .andExpect(jsonPath("$.repositoryScope.repositories[0].repositoryKey")
                        .value("agent-web"))
                .andExpect(jsonPath("$.repositoryScope.repositories[0].primary")
                        .value(true))
                .andExpect(jsonPath(
                        "$.repositoryScope.repositories[0].repositoryRoot")
                        .doesNotExist())
                .andExpect(jsonPath("$.creationSnapshot.snapshotId")
                        .value("snapshot-1"))
                .andExpect(jsonPath("$.stages[0].stageInstanceIdentifier")
                        .value("stage-requirement"))
                .andExpect(jsonPath("$.stages[0].definitionIdentifier")
                        .value("requirement-analysis"))
                .andExpect(jsonPath("$.stages[0].currentConversation.sessionId")
                        .value("conversation-1"))
                .andExpect(jsonPath("$.stages[0].activeRun.runId").value("run-1"))
                .andExpect(jsonPath("$.phases").doesNotExist());

        verify(queryService).findDetailByOwner(OWNER_ID, WORKBENCH_ID);
    }

    @Test
    void missingAndForeignDetailShouldShare404Contract() throws Exception {
        when(queryService.findDetailByOwner(OWNER_ID, "workbench-missing"))
                .thenReturn(Optional.empty());
        when(queryService.findDetailByOwner(OWNER_ID, "workbench-foreign"))
                .thenReturn(Optional.empty());

        mvc.perform(get("/api/workbenches/workbench-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKBENCH_NOT_FOUND"));
        mvc.perform(get("/api/workbenches/workbench-foreign"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKBENCH_NOT_FOUND"));
    }

    @Test
    void archiveShouldRequireAndPassExpectedVersionWithRealOwner() throws Exception {
        WorkbenchLifecycleResult result = lifecycleResult(
                WorkbenchStatus.ARCHIVED, 5L, true);
        when(lifecycleAppService.archive(
                OwnerReference.of(OWNER_ID, OWNER_NAME), WorkbenchId.of(WORKBENCH_ID), 4L))
                .thenReturn(result);

        mvc.perform(post("/api/workbenches/{workbenchId}/archive", WORKBENCH_ID)
                        .header("If-Match", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workbenchId").value(WORKBENCH_ID))
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.changed").value(true));

        verify(lifecycleAppService).archive(
                OwnerReference.of(OWNER_ID, OWNER_NAME), WorkbenchId.of(WORKBENCH_ID), 4L);
    }

    @Test
    void completeAndReopenStageShouldPassStableInstanceIdentifierAndOwner()
            throws Exception {
        OwnerReference owner = OwnerReference.of(OWNER_ID, OWNER_NAME);
        WorkbenchId workbenchId = WorkbenchId.of(WORKBENCH_ID);
        String stageInstanceIdentifier = "stage-requirement";
        WorkbenchStageLifecycleResult completed = stageResult(
                stageInstanceIdentifier, WorkbenchStageStatus.HUMAN_COMPLETED,
                5L, true);
        WorkbenchStageLifecycleResult reopened = stageResult(
                stageInstanceIdentifier, WorkbenchStageStatus.NOT_STARTED,
                6L, true);
        when(lifecycleAppService.completeStage(
                owner, workbenchId, stageInstanceIdentifier, 4L))
                .thenReturn(completed);
        when(lifecycleAppService.reopenStage(
                owner, workbenchId, stageInstanceIdentifier, 5L))
                .thenReturn(reopened);

        mvc.perform(post("/api/workbenches/{workbenchId}/stages/{stageId}/complete",
                        WORKBENCH_ID, stageInstanceIdentifier)
                        .header("If-Match", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workbenchId").value(WORKBENCH_ID))
                .andExpect(jsonPath("$.stageInstanceIdentifier")
                        .value(stageInstanceIdentifier))
                .andExpect(jsonPath("$.definitionIdentifier")
                        .value("requirement-analysis"))
                .andExpect(jsonPath("$.stageStatus")
                        .value("HUMAN_COMPLETED"))
                .andExpect(jsonPath("$.conversationId").value("conversation-1"))
                .andExpect(jsonPath("$.conversationGeneration").value(1))
                .andExpect(jsonPath("$.workbenchVersion").value(5))
                .andExpect(jsonPath("$.changed").value(true));
        mvc.perform(post("/api/workbenches/{workbenchId}/stages/{stageId}/reopen",
                        WORKBENCH_ID, stageInstanceIdentifier)
                        .header("If-Match", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stageStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.workbenchVersion").value(6));

        verify(lifecycleAppService).completeStage(
                owner, workbenchId, stageInstanceIdentifier, 4L);
        verify(lifecycleAppService).reopenStage(
                owner, workbenchId, stageInstanceIdentifier, 5L);
    }

    @Test
    void stageLifecycleShouldRequireExpectedVersionBeforeApplicationCall()
            throws Exception {
        mvc.perform(post("/api/workbenches/{workbenchId}/stages/{stageId}/complete",
                        WORKBENCH_ID, "stage-requirement"))
                .andExpect(status().isBadRequest());

        verify(lifecycleAppService, never())
                .completeStage(any(), any(), any(), anyLong());
    }

    @Test
    void unknownStageShouldReturnStable404Contract() throws Exception {
        when(lifecycleAppService.completeStage(any(), any(), eq("stage-unknown"),
                eq(4L))).thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.STAGE_NOT_FOUND,
                        "Workbench Stage does not exist: stage-unknown"));

        mvc.perform(post("/api/workbenches/{workbenchId}/stages/{stageId}/complete",
                        WORKBENCH_ID, "stage-unknown")
                        .header("If-Match", "4"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_STAGE_NOT_FOUND"));
    }

    @Test
    void staleStageExpectedVersionShouldReturnStable409Contract()
            throws Exception {
        when(lifecycleAppService.reopenStage(any(), any(),
                eq("stage-requirement"), eq(4L)))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.VERSION_CONFLICT,
                        "stale workbench version"));

        mvc.perform(post("/api/workbenches/{workbenchId}/stages/{stageId}/reopen",
                        WORKBENCH_ID, "stage-requirement")
                        .header("If-Match", "4"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_VERSION_CONFLICT"));
    }

    @Test
    void missingExpectedVersionShouldReturn400BeforeLifecycleCall() throws Exception {
        mvc.perform(post("/api/workbenches/{workbenchId}/archive", WORKBENCH_ID))
                .andExpect(status().isBadRequest());

        verify(lifecycleAppService, never()).archive(any(), any(), anyLong());
    }

    @Test
    void lifecycleNotFoundShouldUseSameOwner404Contract() throws Exception {
        when(lifecycleAppService.archive(any(), any(), eq(4L)))
                .thenThrow(new WorkbenchNotFoundException());

        mvc.perform(post("/api/workbenches/{workbenchId}/archive", WORKBENCH_ID)
                        .header("If-Match", "4"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKBENCH_NOT_FOUND"));
    }

    @Test
    void staleExpectedVersionShouldReturnStable409Contract() throws Exception {
        when(lifecycleAppService.archive(any(), any(), eq(4L)))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.VERSION_CONFLICT,
                        "stale workbench version"));

        mvc.perform(post("/api/workbenches/{workbenchId}/archive", WORKBENCH_ID)
                        .header("If-Match", "4"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("stale workbench version"));
    }

    private WorkbenchDetailView detailView() {
        WorkbenchDetailView.RepositoryView repository =
                new WorkbenchDetailView.RepositoryView(
                        "agent-web", "agent-web", true);
        WorkbenchDetailView.RepositoryScopeView scope =
                new WorkbenchDetailView.RepositoryScopeView(
                        "scope-hash", "agent-web", "/test/workspace",
                        Collections.singletonList(repository));
        WorkbenchDetailView.CreationSnapshotView snapshot =
                new WorkbenchDetailView.CreationSnapshotView(
                        "snapshot-1", "topology-hash", "state-hash", 1);
        WorkbenchDetailView.ConversationView currentConversation =
                new WorkbenchDetailView.ConversationView(
                        "conversation-1", 1, 1200L, null);
        WorkbenchDetailView.ConversationView retiredConversation =
                new WorkbenchDetailView.ConversationView(
                        "conversation-0", 0, 1000L, 1100L);
        WorkbenchDetailView.ActiveRunView activeRun =
                new WorkbenchDetailView.ActiveRunView(
                        "run-1", "DISCUSS_READ_ONLY", 1300L);
        WorkbenchDetailView.StageView stage =
                new WorkbenchDetailView.StageView(
                "stage-requirement", "requirement-analysis", 1L,
                "definition-hash", "snapshot-hash", 10,
                "需求分析", "澄清目标和约束",
                Collections.singletonList("DISCUSS_READ_ONLY"),
                "IN_PROGRESS", 1,
                currentConversation,
                Arrays.asList(retiredConversation, currentConversation),
                activeRun, 1300L, null);
        return new WorkbenchDetailView(
                WORKBENCH_ID, "Workbench MVP", "实现本地开发工作台",
                "CODEX", "local", "run-1", false, null, "ACTIVE",
                1000L, 1300L, 7L, scope, snapshot,
                Collections.singletonList(stage));
    }

    private WorkbenchLifecycleResult lifecycleResult(
            WorkbenchStatus status, long version, boolean changed) {
        WorkbenchLifecycleResult result = mock(WorkbenchLifecycleResult.class);
        when(result.getWorkbenchId()).thenReturn(WORKBENCH_ID);
        when(result.getStatus()).thenReturn(status);
        when(result.getVersion()).thenReturn(version);
        when(result.isChanged()).thenReturn(changed);
        return result;
    }

    private WorkbenchStageLifecycleResult stageResult(
            String stageInstanceIdentifier, WorkbenchStageStatus status,
            long version, boolean changed) {
        WorkbenchStageLifecycleResult result = mock(
                WorkbenchStageLifecycleResult.class);
        when(result.getWorkbenchId()).thenReturn(WORKBENCH_ID);
        when(result.getStageInstanceIdentifier())
                .thenReturn(stageInstanceIdentifier);
        when(result.getDefinitionIdentifier())
                .thenReturn("requirement-analysis");
        when(result.getStageStatus()).thenReturn(status);
        when(result.getConversationId()).thenReturn("conversation-1");
        when(result.getConversationGeneration()).thenReturn(1);
        when(result.getWorkbenchVersion()).thenReturn(version);
        when(result.isChanged()).thenReturn(changed);
        return result;
    }
}
