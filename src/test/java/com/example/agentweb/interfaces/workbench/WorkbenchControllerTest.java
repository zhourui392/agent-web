package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.CreateWorkbenchCommand;
import com.example.agentweb.app.workbench.WorkbenchCreationAppService;
import com.example.agentweb.app.workbench.WorkbenchCreationResult;
import com.example.agentweb.app.workbench.WorkbenchLifecycleAppService;
import com.example.agentweb.app.workbench.WorkspaceInspectionAppService;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceInspection;
import com.example.agentweb.app.workbench.WorkspaceInspectionSource;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.WorkspaceRepositoryCandidate;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchStatus;
import com.example.agentweb.interfaces.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Workspace Inspect 与 Workbench Create 的 HTTP 边界测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@WebMvcTest({WorkspaceInspectionController.class, WorkbenchController.class})
@Import({GlobalExceptionHandler.class, WorkbenchExceptionHandler.class})
class WorkbenchControllerTest {

    private static final String WORKSPACE_ROOT = "/home/ubuntu/workspace";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private WorkspaceInspectionAppService inspectionAppService;

    @MockBean
    private WorkbenchCreationAppService creationAppService;

    @MockBean
    private WorkbenchQueryService queryService;

    @MockBean
    private WorkbenchLifecycleAppService lifecycleAppService;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @Test
    void inspectShouldExposeNonSensitiveWorkspaceCandidateContract() throws Exception {
        WorkspaceRepositoryCandidate candidate = new WorkspaceRepositoryCandidate(
                "agent-web", "agent-web", "master", "fae8007", false,
                true, true, Collections.singletonList("working tree is dirty"));
        WorkspaceInspection inspection = new WorkspaceInspection(
                WORKSPACE_ROOT, "inspection-1", WorkspaceInspectionSource.DISCOVERY,
                Collections.singletonList(candidate),
                Collections.singletonList("review repository selection"));
        when(inspectionAppService.inspect(WORKSPACE_ROOT)).thenReturn(inspection);

        mvc.perform(post("/api/workbench/workspaces/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceRoot\":\"" + WORKSPACE_ROOT + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceRootDisplay").value(WORKSPACE_ROOT))
                .andExpect(jsonPath("$.inspectionToken").value("inspection-1"))
                .andExpect(jsonPath("$.source").value("DISCOVERY"))
                .andExpect(jsonPath("$.repositories[0].repositoryKey").value("agent-web"))
                .andExpect(jsonPath("$.repositories[0].relativePath").value("agent-web"))
                .andExpect(jsonPath("$.repositories[0].branch").value("master"))
                .andExpect(jsonPath("$.repositories[0].headShort").value("fae8007"))
                .andExpect(jsonPath("$.repositories[0].clean").value(false))
                .andExpect(jsonPath("$.repositories[0].selectedByDefault").value(true))
                .andExpect(jsonPath("$.repositories[0].primarySuggested").value(true))
                .andExpect(jsonPath("$.repositories[0].warnings[0]")
                        .value("working tree is dirty"))
                .andExpect(jsonPath("$.warnings[0]")
                        .value("review repository selection"));

        verify(inspectionAppService).inspect(WORKSPACE_ROOT);
    }

    @Test
    void inspectWithBlankWorkspaceRootShouldReturn400BeforeApplicationCall() throws Exception {
        mvc.perform(post("/api/workbench/workspaces/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceRoot\":\"  \"}"))
                .andExpect(status().isBadRequest());

        verify(inspectionAppService, never()).inspect(any());
    }

    @Test
    void workspaceFailureShouldUseStableCodeAndDesignedHttpStatus() throws Exception {
        when(inspectionAppService.inspect(WORKSPACE_ROOT)).thenThrow(
                new WorkspaceOperationException(
                        WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                        "workspace path is outside allowed roots"));

        mvc.perform(post("/api/workbench/workspaces/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceRoot\":\"" + WORKSPACE_ROOT + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_PATH_FORBIDDEN"))
                .andExpect(jsonPath("$.message")
                        .value("workspace path is outside allowed roots"));
    }

    @Test
    void createShouldUseAuthenticatedOwnerHeaderAndRequestDto() throws Exception {
        WorkbenchCreationResult result = org.mockito.Mockito.mock(
                WorkbenchCreationResult.class);
        when(result.getWorkbenchId()).thenReturn("workbench-1");
        when(result.getStatus()).thenReturn(WorkbenchStatus.ACTIVE);
        when(result.getVersion()).thenReturn(0L);
        when(result.isReplayed()).thenReturn(false);
        when(currentUserProvider.currentUserId()).thenReturn("owner-1");
        when(currentUserProvider.currentUserName()).thenReturn("Alex");
        when(creationAppService.create(any(), any())).thenReturn(result);

        mvc.perform(post("/api/workbenches")
                        .header("Idempotency-Key", "create-workbench-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/workbenches/workbench-1"))
                .andExpect(jsonPath("$.workbenchId").value("workbench-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.replayed").value(false));

        ArgumentCaptor<OwnerReference> actor =
                ArgumentCaptor.forClass(OwnerReference.class);
        ArgumentCaptor<CreateWorkbenchCommand> command =
                ArgumentCaptor.forClass(CreateWorkbenchCommand.class);
        verify(creationAppService).create(actor.capture(), command.capture());
        assertEquals("owner-1", actor.getValue().getOwnerId());
        assertEquals("Alex", actor.getValue().getOwnerName());
        assertEquals("create-workbench-1", command.getValue().getIdempotencyKey());
        assertEquals("Workbench MVP", command.getValue().getTitle());
        assertEquals("实现本地开发工作台", command.getValue().getOriginalGoal());
        assertEquals(AgentType.CODEX, command.getValue().getAgentType());
        assertNull(command.getValue().getEnvironment());
        assertEquals(WORKSPACE_ROOT, command.getValue().getWorkspaceRoot());
        assertEquals("agent-web",
                command.getValue().getRepositorySelection().getPrimaryRepositoryKey());
        assertEquals(Arrays.asList("agent-web", "shared-library"),
                command.getValue().getRepositorySelection().getRepositoryKeys());
    }

    @Test
    void createWithoutIdempotencyKeyShouldReturn400BeforeApplicationCall() throws Exception {
        mvc.perform(post("/api/workbenches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isBadRequest());

        verify(creationAppService, never()).create(any(), any());
    }

    @Test
    void createWithInvalidAgentTypeShouldReturn400BeforeApplicationCall() throws Exception {
        when(currentUserProvider.currentUserId()).thenReturn("owner-1");
        when(currentUserProvider.currentUserName()).thenReturn("Alex");

        mvc.perform(post("/api/workbenches")
                        .header("Idempotency-Key", "create-workbench-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody().replace("CODEX", "UNKNOWN")))
                .andExpect(status().isBadRequest());

        verify(creationAppService, never()).create(any(), any());
    }

    @Test
    void createWithEmptyRepositorySelectionShouldReturn400BeforeApplicationCall()
            throws Exception {
        mvc.perform(post("/api/workbenches")
                        .header("Idempotency-Key", "create-workbench-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody().replace(
                                "[\"shared-library\",\"agent-web\"]", "[]")))
                .andExpect(status().isBadRequest());

        verify(creationAppService, never()).create(any(), any());
    }

    private String validCreateBody() {
        return "{"
                + "\"title\":\"Workbench MVP\","
                + "\"originalGoal\":\"实现本地开发工作台\","
                + "\"agentType\":\"CODEX\","
                + "\"environment\":null,"
                + "\"workspaceRoot\":\"" + WORKSPACE_ROOT + "\","
                + "\"primaryRepository\":\"agent-web\","
                + "\"repositories\":[\"shared-library\",\"agent-web\"]"
                + "}";
    }
}
