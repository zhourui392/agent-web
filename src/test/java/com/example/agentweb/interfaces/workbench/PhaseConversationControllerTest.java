package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.conversation.PhaseConversationAppService;
import com.example.agentweb.app.workbench.conversation.PhaseConversationResult;
import com.example.agentweb.app.workbench.conversation.RestartPhaseConversationCommand;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.interfaces.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase Conversation restart 的独立 HTTP 边界契约测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@WebMvcTest(PhaseConversationController.class)
@Import({GlobalExceptionHandler.class, WorkbenchExceptionHandler.class})
class PhaseConversationControllerTest {

    private static final String OWNER_ID = "owner-1";
    private static final String OWNER_NAME = "Alex";
    private static final String WORKBENCH_ID = "workbench-1";
    private static final String ROUTE =
            "/api/workbenches/{workbenchId}/phases/{phase}/conversation/restart";
    private static final String ENSURE_ROUTE =
            "/api/workbenches/{workbenchId}/phases/{phase}/conversation";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private PhaseConversationAppService appService;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void authenticateOwner() {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(currentUserProvider.currentUserName()).thenReturn(OWNER_NAME);
    }

    @Test
    void ensureShouldLazilyCreateConversationAndReturnUpdatedVersion()
            throws Exception {
        PhaseConversationResult result = org.mockito.Mockito.mock(
                PhaseConversationResult.class);
        when(result.getSessionId()).thenReturn("phase-session-1");
        when(result.getConversationGeneration()).thenReturn(0);
        when(result.getWorkbenchVersion()).thenReturn(1L);
        when(result.isCreated()).thenReturn(true);
        when(appService.ensureConversation(
                any(), any(), any(), org.mockito.ArgumentMatchers.eq(0L)))
                .thenReturn(result);

        mvc.perform(post(ENSURE_ROUTE, WORKBENCH_ID, "requirement_analysis")
                        .header("If-Match", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("phase-session-1"))
                .andExpect(jsonPath("$.generation").value(0))
                .andExpect(jsonPath("$.workbenchVersion").value(1))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.previousSessionId").doesNotExist())
                .andExpect(jsonPath("$.replayed").doesNotExist());

        ArgumentCaptor<OwnerReference> actor =
                ArgumentCaptor.forClass(OwnerReference.class);
        verify(appService).ensureConversation(
                actor.capture(), org.mockito.ArgumentMatchers.eq(
                        WorkbenchId.of(WORKBENCH_ID)),
                org.mockito.ArgumentMatchers.eq(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS),
                org.mockito.ArgumentMatchers.eq(0L));
        assertEquals(OWNER_ID, actor.getValue().getOwnerId());
    }

    @Test
    void restartShouldUseAuthenticatedOwnerAndHeaderOnlyCommandAndReturnSafeProjection()
            throws Exception {
        PhaseConversationResult result = org.mockito.Mockito.mock(
                PhaseConversationResult.class);
        when(result.getSessionId()).thenReturn("phase-session-2");
        when(result.getPreviousSessionId()).thenReturn("phase-session-1");
        when(result.getConversationGeneration()).thenReturn(2);
        when(result.getWorkbenchVersion()).thenReturn(5L);
        when(result.isReplayed()).thenReturn(false);
        when(appService.restartConversation(any(), any())).thenReturn(result);

        mvc.perform(post(ROUTE, WORKBENCH_ID, "implement_test")
                        .header("Idempotency-Key", "restart-key-1")
                        .header("If-Match", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("phase-session-2"))
                .andExpect(jsonPath("$.previousSessionId").value("phase-session-1"))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.workbenchVersion").value(5))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.workbenchId").doesNotExist())
                .andExpect(jsonPath("$.phase").doesNotExist())
                .andExpect(jsonPath("$.created").doesNotExist())
                .andExpect(jsonPath("$.workingDir").doesNotExist())
                .andExpect(jsonPath("$.repositoryRoot").doesNotExist())
                .andExpect(jsonPath("$.environment").doesNotExist())
                .andExpect(jsonPath("$.env").doesNotExist())
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.ownerName").doesNotExist())
                .andExpect(content().string(not(containsString(
                        "/home/ubuntu/workspace/agent-web"))));

        ArgumentCaptor<OwnerReference> actor =
                ArgumentCaptor.forClass(OwnerReference.class);
        ArgumentCaptor<RestartPhaseConversationCommand> command =
                ArgumentCaptor.forClass(RestartPhaseConversationCommand.class);
        verify(appService).restartConversation(actor.capture(), command.capture());
        assertEquals(OWNER_ID, actor.getValue().getOwnerId());
        assertEquals(OWNER_NAME, actor.getValue().getOwnerName());
        assertEquals(WorkbenchId.of(WORKBENCH_ID), command.getValue().getWorkbenchId());
        assertEquals(WorkbenchPhase.IMPLEMENT_TEST, command.getValue().getPhase());
        assertEquals("restart-key-1", command.getValue().getIdempotencyKey());
        assertEquals(4L, command.getValue().getExpectedVersion());
    }

    @Test
    void restartShouldRequireIdempotencyKeyAndExpectedVersionBeforeApplicationCall()
            throws Exception {
        mvc.perform(post(ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("If-Match", "4"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("Idempotency-Key", "restart-key-1"))
                .andExpect(status().isBadRequest());

        verify(appService, never()).restartConversation(any(), any());
    }

    @Test
    void restartShouldRejectInvalidPhaseBeforeApplicationCall() throws Exception {
        mvc.perform(post(ROUTE, WORKBENCH_ID, "unknown")
                        .header("Idempotency-Key", "restart-key-1")
                        .header("If-Match", "4"))
                .andExpect(status().isBadRequest());

        verify(appService, never()).restartConversation(any(), any());
    }

    @Test
    void missingAndForeignWorkbenchShouldShare404Contract() throws Exception {
        when(appService.restartConversation(any(), any()))
                .thenThrow(new WorkbenchNotFoundException());

        mvc.perform(post(ROUTE, "workbench-missing", "IMPLEMENT_TEST")
                        .header("Idempotency-Key", "restart-missing")
                        .header("If-Match", "4"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKBENCH_NOT_FOUND"));
        mvc.perform(post(ROUTE, "workbench-foreign", "IMPLEMENT_TEST")
                        .header("Idempotency-Key", "restart-foreign")
                        .header("If-Match", "4"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKBENCH_NOT_FOUND"));
    }

    @Test
    void archivedWorkbenchShouldReturn410() throws Exception {
        when(appService.restartConversation(any(), any())).thenThrow(
                new WorkbenchDomainException(
                        WorkbenchErrorCode.ARCHIVED, "workbench is archived"));

        mvc.perform(post(ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("Idempotency-Key", "restart-key-1")
                        .header("If-Match", "4"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("WORKBENCH_ARCHIVED"));
    }

    @Test
    void activeRunAndNonInProgressPhaseShouldReturn409() throws Exception {
        when(appService.restartConversation(any(), any()))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.PHASE_RESTART_INVALID,
                        "phase has an active run"))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.PHASE_RESTART_INVALID,
                        "phase is not in progress"));

        mvc.perform(post(ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("Idempotency-Key", "restart-active")
                        .header("If-Match", "4"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_PHASE_RESTART_INVALID"));
        mvc.perform(post(ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("Idempotency-Key", "restart-not-in-progress")
                        .header("If-Match", "4"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_PHASE_RESTART_INVALID"));
    }

    @Test
    void versionAndIdempotencyConflictShouldReturn409() throws Exception {
        when(appService.restartConversation(any(), any()))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.VERSION_CONFLICT,
                        "stale workbench version"))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                        "restart key belongs to another request"));

        mvc.perform(post(ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("Idempotency-Key", "restart-version")
                        .header("If-Match", "4"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_VERSION_CONFLICT"));
        mvc.perform(post(ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("Idempotency-Key", "restart-conflict")
                        .header("If-Match", "4"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_IDEMPOTENCY_CONFLICT"));
    }
}
