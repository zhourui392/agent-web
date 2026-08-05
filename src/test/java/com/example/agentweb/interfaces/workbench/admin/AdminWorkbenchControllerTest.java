package com.example.agentweb.interfaces.workbench.admin;

import com.example.agentweb.app.workbench.admin.AdminWorkbenchDetailView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchListItemView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchListPage;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchQueryService;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchReconciliationException;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunActionResult;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunAppService;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunDetailView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunListItemView;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunListPage;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.auth.UserRole;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchAdminAction;
import com.example.agentweb.domain.workbench.WorkbenchAdministrator;
import com.example.agentweb.domain.workbench.WorkbenchId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 独立 Admin Workbench API、安全投影与禁止 Owner 变更面的接口测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class AdminWorkbenchControllerTest {

    private AdminWorkbenchQueryService queryService;
    private AdminWorkbenchRunAppService runAppService;
    private UserContext userContext;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queryService = mock(AdminWorkbenchQueryService.class);
        runAppService = mock(AdminWorkbenchRunAppService.class);
        userContext = mock(UserContext.class);
        when(userContext.currentUser()).thenReturn(Optional.of(
                new LoginUser("admin-7", "On Call Admin", null,
                        UserRole.ADMIN)));
        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminWorkbenchController(
                                queryService, runAppService, userContext))
                .setControllerAdvice(new AdminWorkbenchExceptionHandler())
                .build();
    }

    @Test
    void shouldExposeIndependentSafeWorkbenchAndRunProjections()
            throws Exception {
        when(queryService.list(any())).thenReturn(
                new AdminWorkbenchListPage(Collections.singletonList(
                        new AdminWorkbenchListItemView(
                                "workbench-1", "owner-1", "Owner One",
                                "Workbench", "ACTIVE", "CODEX", "local",
                                "agent-web", 1, "run-1", 1L, 2L, 3L)),
                        null));
        when(queryService.findDetail("workbench-1"))
                .thenReturn(Optional.of(detail()));
        when(queryService.listRuns(any(), any())).thenReturn(
                new AdminWorkbenchRunListPage(Collections.singletonList(
                        runListItem()), null));
        when(queryService.findRunDetail("workbench-1", "run-1"))
                .thenReturn(Optional.of(runDetail()));

        mvc.perform(get("/api/admin/workbenches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ownerId").value("owner-1"))
                .andExpect(jsonPath("$.items[0].workspaceRoot").doesNotExist());
        mvc.perform(get("/api/admin/workbenches/workbench-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value("owner-1"))
                .andExpect(jsonPath("$.originalGoal").doesNotExist())
                .andExpect(jsonPath("$.repositories[0].repositoryRoot")
                        .doesNotExist());
        mvc.perform(get("/api/admin/workbenches/workbench-1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].runId").value("run-1"));
        mvc.perform(get("/api/admin/workbenches/workbench-1/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.toolOutput").doesNotExist());
    }

    @Test
    void stopAndReconcileShouldUseLoggedInAdministratorNotRequestOwner()
            throws Exception {
        when(runAppService.stop(any(), any(), any())).thenReturn(
                new AdminWorkbenchRunActionResult(
                        "workbench-1", "run-1", WorkbenchAdminAction.STOP,
                        "REQUESTED", "CANCEL_REQUESTED", 10L));
        when(runAppService.reconcile(any(), any(), any())).thenReturn(
                new AdminWorkbenchRunActionResult(
                        "workbench-1", "run-1",
                        WorkbenchAdminAction.RECONCILE,
                        "INTERRUPT", null, 11L));

        mvc.perform(post("/api/admin/workbenches/workbench-1/runs/run-1/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("REQUESTED"));
        mvc.perform(post("/api/admin/workbenches/workbench-1/runs/run-1/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("INTERRUPT"));

        ArgumentCaptor<WorkbenchAdministrator> actor =
                ArgumentCaptor.forClass(WorkbenchAdministrator.class);
        verify(runAppService).stop(actor.capture(),
                org.mockito.ArgumentMatchers.eq(
                        WorkbenchId.of("workbench-1")),
                org.mockito.ArgumentMatchers.eq("run-1"));
        assertEquals("admin-7", actor.getValue().getActorId());
        assertEquals("On Call Admin", actor.getValue().getActorName());
    }

    @Test
    void mutationBodiesShouldBeStrictlyValidated()
            throws Exception {
        mvc.perform(post("/api/admin/workbenches/workbench-1/runs/run-1/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"owner-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_ADMIN_REQUEST_INVALID"));
        verify(runAppService, never()).stop(any(), any(), any());

        mvc.perform(post("/api/admin/workbenches/workbench-1/runs/run-1/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_ADMIN_REQUEST_INVALID"));
        verify(runAppService, never()).reconcile(any(), any(), any());

    }

    @Test
    void controllerShouldFailClosedEvenIfServletFilterWasBypassed()
            throws Exception {
        when(userContext.currentUser()).thenReturn(Optional.of(
                new LoginUser("user-1", "Normal User", null,
                        UserRole.USER)));

        mvc.perform(get("/api/admin/workbenches"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_ADMIN_FORBIDDEN"));

        verify(queryService, never()).list(any());
    }

    @Test
    void missingResourcesAndReconciliationFailureShouldUseSafeStableErrors()
            throws Exception {
        when(queryService.findDetail("missing"))
                .thenReturn(Optional.empty());
        when(queryService.findRunDetail("workbench-1", "missing-run"))
                .thenReturn(Optional.empty());
        when(runAppService.reconcile(any(), any(), any()))
                .thenThrow(new AdminWorkbenchReconciliationException(
                        new IllegalStateException(
                                "/workspace/secret.env raw stderr")));

        mvc.perform(get("/api/admin/workbenches/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_NOT_FOUND"));
        mvc.perform(get("/api/admin/workbenches/workbench-1/runs/missing-run"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_RUN_NOT_FOUND"));
        mvc.perform(post("/api/admin/workbenches/workbench-1/runs/run-1/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_ADMIN_RECONCILIATION_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        "workbench run reconciliation failed"));
    }

    @Test
    void unexpectedFailureShouldNotExposeInternalPathOrStderr()
            throws Exception {
        when(runAppService.stop(any(), any(), any()))
                .thenThrow(new IllegalStateException(
                        "/workspace/secret.env raw provider stderr"));

        mvc.perform(post("/api/admin/workbenches/workbench-1/runs/run-1/stop")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_ADMIN_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(
                        "admin workbench operation failed"));
    }

    private AdminWorkbenchDetailView detail() {
        return new AdminWorkbenchDetailView(
                "workbench-1", "owner-1", "Owner One", "Workbench",
                "ACTIVE", "CODEX", "local", "agent-web", hash('a'),
                "run-1", 1L, 2L, 3L,
                Collections.singletonList(
                        new AdminWorkbenchDetailView.RepositoryView(
                                "agent-web", "agent-web", true)),
                Collections.singletonList(
                        new AdminWorkbenchDetailView.StageView(
                                "stage-implement", "implementation", 1L,
                                2, "IN_PROGRESS",
                                "run-1", "MODIFY_WORKSPACE", 2L, null)));
    }

    private AdminWorkbenchRunListItemView runListItem() {
        return new AdminWorkbenchRunListItemView(
                "run-1", "workbench-1", "stage-implement",
                ChatRunStatus.RUNNING, RunMode.MODIFY_WORKSPACE,
                7L, 1L, 2L, null, null, null);
    }

    private AdminWorkbenchRunDetailView runDetail() {
        return new AdminWorkbenchRunDetailView(
                "run-1", "workbench-1", "stage-implement",
                ChatRunStatus.RUNNING, RunMode.MODIFY_WORKSPACE,
                7L, 1L, 2L, null, null, null, null,
                hash('a'), hash('b'), hash('c'), true);
    }

    private static String hash(char value) {
        return String.join("", Collections.nCopies(64,
                String.valueOf(value)));
    }
}
