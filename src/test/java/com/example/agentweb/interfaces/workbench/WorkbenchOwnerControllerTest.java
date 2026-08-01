package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.WorkbenchCreationAppService;
import com.example.agentweb.app.workbench.WorkbenchLifecycleAppService;
import com.example.agentweb.app.workbench.WorkbenchLifecycleResult;
import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.WorkbenchPhaseLifecycleResult;
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
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchPhaseStatus;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
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
                .andExpect(jsonPath("$.phases[0].phase")
                        .value("REQUIREMENT_ANALYSIS"))
                .andExpect(jsonPath("$.phases[0].currentConversation.sessionId")
                        .value("conversation-1"))
                .andExpect(jsonPath("$.phases[0].activeRun.runId").value("run-1"));

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
    void completeAndReopenShouldParsePhaseAndPassExpectedVersion() throws Exception {
        OwnerReference owner = OwnerReference.of(OWNER_ID, OWNER_NAME);
        WorkbenchId workbenchId = WorkbenchId.of(WORKBENCH_ID);
        WorkbenchPhaseLifecycleResult completed = phaseResult(
                WorkbenchPhaseStatus.HUMAN_COMPLETED, 5L, true);
        WorkbenchPhaseLifecycleResult reopened = phaseResult(
                WorkbenchPhaseStatus.IN_PROGRESS, 6L, true);
        when(lifecycleAppService.completePhase(
                owner, workbenchId, WorkbenchPhase.REQUIREMENT_ANALYSIS, 4L))
                .thenReturn(completed);
        when(lifecycleAppService.reopenPhase(
                owner, workbenchId, WorkbenchPhase.REQUIREMENT_ANALYSIS, 5L))
                .thenReturn(reopened);

        mvc.perform(post("/api/workbenches/{workbenchId}/phases/{phase}/complete",
                        WORKBENCH_ID, "requirement_analysis")
                        .header("If-Match", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("REQUIREMENT_ANALYSIS"))
                .andExpect(jsonPath("$.phaseStatus").value("HUMAN_COMPLETED"))
                .andExpect(jsonPath("$.workbenchVersion").value(5));
        mvc.perform(post("/api/workbenches/{workbenchId}/phases/{phase}/reopen",
                        WORKBENCH_ID, "requirement_analysis")
                        .header("If-Match", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phaseStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.workbenchVersion").value(6));

        verify(lifecycleAppService).completePhase(
                owner, workbenchId, WorkbenchPhase.REQUIREMENT_ANALYSIS, 4L);
        verify(lifecycleAppService).reopenPhase(
                owner, workbenchId, WorkbenchPhase.REQUIREMENT_ANALYSIS, 5L);
    }

    @Test
    void missingExpectedVersionShouldReturn400BeforeLifecycleCall() throws Exception {
        mvc.perform(post("/api/workbenches/{workbenchId}/archive", WORKBENCH_ID))
                .andExpect(status().isBadRequest());

        verify(lifecycleAppService, never()).archive(any(), any(), anyLong());
    }

    @Test
    void invalidPhaseShouldReturn400BeforeLifecycleCall() throws Exception {
        mvc.perform(post("/api/workbenches/{workbenchId}/phases/{phase}/complete",
                        WORKBENCH_ID, "unknown")
                        .header("If-Match", "4"))
                .andExpect(status().isBadRequest());

        verify(lifecycleAppService, never())
                .completePhase(any(), any(), any(), anyLong());
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
                        "scope-hash", "agent-web",
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
                        "run-1", "DISCUSS_READ_ONLY", 1300L,
                        null, null, null);
        WorkbenchDetailView.PhaseView phase = new WorkbenchDetailView.PhaseView(
                "REQUIREMENT_ANALYSIS", 0, "IN_PROGRESS", 1,
                currentConversation,
                Arrays.asList(retiredConversation, currentConversation),
                activeRun, 1300L, null);
        return new WorkbenchDetailView(
                WORKBENCH_ID, "Workbench MVP", "实现本地开发工作台",
                "CODEX", "local", "run-1", "ACTIVE",
                1000L, 1300L, 7L, scope, snapshot,
                Collections.singletonList(phase));
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

    private WorkbenchPhaseLifecycleResult phaseResult(
            WorkbenchPhaseStatus status, long version, boolean changed) {
        WorkbenchPhaseLifecycleResult result = mock(
                WorkbenchPhaseLifecycleResult.class);
        when(result.getWorkbenchId()).thenReturn(WORKBENCH_ID);
        when(result.getPhase()).thenReturn(WorkbenchPhase.REQUIREMENT_ANALYSIS);
        when(result.getPhaseStatus()).thenReturn(status);
        when(result.getConversationId()).thenReturn("conversation-1");
        when(result.getConversationGeneration()).thenReturn(1);
        when(result.getWorkbenchVersion()).thenReturn(version);
        when(result.isChanged()).thenReturn(changed);
        return result;
    }
}
