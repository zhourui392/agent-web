package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.conversation.RestartWorkbenchStageConversationCommand;
import com.example.agentweb.app.workbench.conversation.WorkbenchStageConversationAppService;
import com.example.agentweb.app.workbench.conversation.WorkbenchStageConversationResult;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
import com.example.agentweb.app.workbench.query.WorkbenchStageConversationMessagePage;
import com.example.agentweb.app.workbench.query.WorkbenchStageConversationMessageRequest;
import com.example.agentweb.app.workbench.query.WorkbenchStageConversationMessageTooLargeException;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
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
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 动态 Stage Conversation 的 Owner HTTP 边界契约测试。
 *
 * @author alex
 * @since 2026-08-05
 */
@WebMvcTest(WorkbenchStageConversationController.class)
@Import({GlobalExceptionHandler.class, WorkbenchExceptionHandler.class})
class WorkbenchStageConversationControllerTest {

    private static final String OWNER_ID = "owner-1";
    private static final String OWNER_NAME = "Alex";
    private static final String WORKBENCH_ID = "workbench-1";
    private static final String STAGE_INSTANCE_IDENTIFIER = "stage-instance-2";
    private static final String CONVERSATION_ROUTE =
            "/api/workbenches/{workbenchId}/stages/{stageInstanceIdentifier}/conversation";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private WorkbenchStageConversationAppService appService;

    @MockBean
    private WorkbenchQueryService queryService;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void authenticateOwner() {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(currentUserProvider.currentUserName()).thenReturn(OWNER_NAME);
    }

    @Test
    void ensureShouldUseStageInstanceIdentityAndReturnSafeProjection()
            throws Exception {
        WorkbenchStageConversationResult result = org.mockito.Mockito.mock(
                WorkbenchStageConversationResult.class);
        when(result.getSessionId()).thenReturn("stage-session-1");
        when(result.getConversationGeneration()).thenReturn(0);
        when(result.getWorkbenchVersion()).thenReturn(2L);
        when(result.isCreated()).thenReturn(true);
        when(appService.ensureConversation(any(), any(), any(), eq(1L)))
                .thenReturn(result);

        mvc.perform(post(CONVERSATION_ROUTE, WORKBENCH_ID,
                        STAGE_INSTANCE_IDENTIFIER)
                        .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("stage-session-1"))
                .andExpect(jsonPath("$.generation").value(0))
                .andExpect(jsonPath("$.workbenchVersion").value(2))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.workbenchId").doesNotExist())
                .andExpect(jsonPath("$.stageInstanceIdentifier").doesNotExist())
                .andExpect(jsonPath("$.definitionIdentifier").doesNotExist())
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.environment").doesNotExist());

        ArgumentCaptor<OwnerReference> actor =
                ArgumentCaptor.forClass(OwnerReference.class);
        verify(appService).ensureConversation(
                actor.capture(), eq(WorkbenchId.of(WORKBENCH_ID)),
                eq(STAGE_INSTANCE_IDENTIFIER), eq(1L));
        assertEquals(OWNER_ID, actor.getValue().getOwnerId());
        assertEquals(OWNER_NAME, actor.getValue().getOwnerName());
    }

    @Test
    void ensureShouldRequireExpectedVersionBeforeApplicationCall()
            throws Exception {
        mvc.perform(post(CONVERSATION_ROUTE, WORKBENCH_ID,
                        STAGE_INSTANCE_IDENTIFIER))
                .andExpect(status().isBadRequest());

        verify(appService, never()).ensureConversation(
                any(), any(), any(), anyLong());
    }

    @Test
    void messagesShouldQueryExactCurrentStageConversationAndReturnSafePage()
            throws Exception {
        WorkbenchStageConversationMessagePage page =
                new WorkbenchStageConversationMessagePage(
                        "stage-session-1", 1, 4L, Arrays.asList(
                        new WorkbenchStageConversationMessagePage.MessageView(
                                10L, "user", "请检查方案",
                                "2026-08-05T00:00:00Z", "run-1"),
                        new WorkbenchStageConversationMessagePage.MessageView(
                                11L, "assistant", "## 结论",
                                "2026-08-05T00:00:01Z", "run-1")),
                        Long.valueOf(10L));
        when(queryService.findCurrentStageConversationByOwner(
                eq(OWNER_ID), eq(WORKBENCH_ID),
                eq(STAGE_INSTANCE_IDENTIFIER),
                any(WorkbenchStageConversationMessageRequest.class)))
                .thenReturn(Optional.of(page));

        mvc.perform(get(CONVERSATION_ROUTE + "/messages", WORKBENCH_ID,
                        STAGE_INSTANCE_IDENTIFIER)
                        .queryParam("beforeMessageId", "12")
                        .queryParam("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("stage-session-1"))
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.workbenchVersion").value(4))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("## 结论"))
                .andExpect(jsonPath("$.messages[1].runId").value("run-1"))
                .andExpect(jsonPath("$.nextCursor").value(10))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.workspaceRoot").doesNotExist())
                .andExpect(content().string(not(containsString(
                        "/home/ubuntu/workspace"))));

        ArgumentCaptor<WorkbenchStageConversationMessageRequest> request =
                ArgumentCaptor.forClass(
                        WorkbenchStageConversationMessageRequest.class);
        verify(queryService).findCurrentStageConversationByOwner(
                eq(OWNER_ID), eq(WORKBENCH_ID),
                eq(STAGE_INSTANCE_IDENTIFIER), request.capture());
        assertEquals(Long.valueOf(12L), request.getValue().getBeforeMessageId());
        assertEquals(2, request.getValue().getLimit());
    }

    @Test
    void messagesShouldObscureForeignOrUnknownStageAndRejectUnsafePagination()
            throws Exception {
        when(queryService.findCurrentStageConversationByOwner(
                eq(OWNER_ID), eq("foreign-workbench"),
                eq(STAGE_INSTANCE_IDENTIFIER),
                any(WorkbenchStageConversationMessageRequest.class)))
                .thenReturn(Optional.empty());

        mvc.perform(get(CONVERSATION_ROUTE + "/messages",
                        "foreign-workbench", STAGE_INSTANCE_IDENTIFIER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKBENCH_NOT_FOUND"));
        mvc.perform(get(CONVERSATION_ROUTE + "/messages", WORKBENCH_ID,
                        STAGE_INSTANCE_IDENTIFIER)
                        .queryParam("beforeMessageId", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(CONVERSATION_ROUTE + "/messages", WORKBENCH_ID,
                        STAGE_INSTANCE_IDENTIFIER)
                        .queryParam("limit", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void messagesShouldReturnStagePayloadTooLargeContractWithoutContent()
            throws Exception {
        when(queryService.findCurrentStageConversationByOwner(
                eq(OWNER_ID), eq(WORKBENCH_ID),
                eq(STAGE_INSTANCE_IDENTIFIER),
                any(WorkbenchStageConversationMessageRequest.class)))
                .thenThrow(
                        new WorkbenchStageConversationMessageTooLargeException());

        mvc.perform(get(CONVERSATION_ROUTE + "/messages", WORKBENCH_ID,
                        STAGE_INSTANCE_IDENTIFIER))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_STAGE_MESSAGE_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value(
                        "stage conversation message exceeds the safe response limit"))
                .andExpect(content().string(not(containsString("content"))));
    }

    @Test
    void restartShouldRequireHeadersAndReturnIdempotentSafeProjection()
            throws Exception {
        WorkbenchStageConversationResult result = org.mockito.Mockito.mock(
                WorkbenchStageConversationResult.class);
        when(result.getSessionId()).thenReturn("stage-session-2");
        when(result.getPreviousSessionId()).thenReturn("stage-session-1");
        when(result.getConversationGeneration()).thenReturn(2);
        when(result.getWorkbenchVersion()).thenReturn(5L);
        when(result.isReplayed()).thenReturn(false);
        when(appService.restartConversation(any(), any())).thenReturn(result);

        mvc.perform(post(CONVERSATION_ROUTE + "/restart", WORKBENCH_ID,
                        STAGE_INSTANCE_IDENTIFIER)
                        .header("Idempotency-Key", "restart-stage-1")
                        .header("If-Match", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("stage-session-2"))
                .andExpect(jsonPath("$.previousSessionId").value(
                        "stage-session-1"))
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.workbenchVersion").value(5))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.workbenchId").doesNotExist())
                .andExpect(jsonPath("$.stageInstanceIdentifier").doesNotExist())
                .andExpect(jsonPath("$.definitionIdentifier").doesNotExist());

        ArgumentCaptor<RestartWorkbenchStageConversationCommand> command =
                ArgumentCaptor.forClass(
                        RestartWorkbenchStageConversationCommand.class);
        verify(appService).restartConversation(any(), command.capture());
        assertEquals(WorkbenchId.of(WORKBENCH_ID),
                command.getValue().getWorkbenchId());
        assertEquals(STAGE_INSTANCE_IDENTIFIER,
                command.getValue().getStageInstanceIdentifier());
        assertEquals("restart-stage-1",
                command.getValue().getIdempotencyKey());
        assertEquals(4L, command.getValue().getExpectedVersion());

        mvc.perform(post(CONVERSATION_ROUTE + "/restart", WORKBENCH_ID,
                        STAGE_INSTANCE_IDENTIFIER)
                        .header("If-Match", "4"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(CONVERSATION_ROUTE + "/restart", WORKBENCH_ID,
                        STAGE_INSTANCE_IDENTIFIER)
                        .header("Idempotency-Key", "restart-stage-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void restartShouldMapMissingStageAndConflictsWithoutLeakingState()
            throws Exception {
        when(appService.restartConversation(any(), any()))
                .thenThrow(new WorkbenchNotFoundException())
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.STAGE_RESTART_INVALID,
                        "stage cannot restart"))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.VERSION_CONFLICT,
                        "stale version"))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                        "key conflict"));

        mvc.perform(restartRequest("missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKBENCH_NOT_FOUND"));
        mvc.perform(restartRequest("invalid"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_STAGE_RESTART_INVALID"));
        mvc.perform(restartRequest("version"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_VERSION_CONFLICT"));
        mvc.perform(restartRequest("idempotency"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_IDEMPOTENCY_CONFLICT"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            restartRequest(String idempotencyKey) {
        return post(CONVERSATION_ROUTE + "/restart", WORKBENCH_ID,
                STAGE_INSTANCE_IDENTIFIER)
                .header("Idempotency-Key", idempotencyKey)
                .header("If-Match", "4");
    }
}
