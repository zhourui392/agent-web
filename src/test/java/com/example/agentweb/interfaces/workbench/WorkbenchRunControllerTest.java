package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.runtime.port.RuntimePreflightErrorCode;
import com.example.agentweb.app.runtime.port.RuntimePreflightException;
import com.example.agentweb.app.workbench.run.SubmitWorkbenchStageRunCommand;
import com.example.agentweb.app.workbench.run.WorkbenchRunAppService;
import com.example.agentweb.app.workbench.run.WorkbenchRunCursorExpiredException;
import com.example.agentweb.app.workbench.run.WorkbenchRunEvent;
import com.example.agentweb.app.workbench.run.WorkbenchRunHistoryAppService;
import com.example.agentweb.app.workbench.run.WorkbenchRunEventPageRequest;
import com.example.agentweb.app.workbench.run.WorkbenchRunListPage;
import com.example.agentweb.app.workbench.run.WorkbenchRunListRequest;
import com.example.agentweb.app.workbench.run.WorkbenchRunStopResult;
import com.example.agentweb.app.workbench.run.WorkbenchRunStreamHandle;
import com.example.agentweb.app.workbench.run.WorkbenchRunStreamSink;
import com.example.agentweb.app.workbench.run.WorkbenchStageRunAppService;
import com.example.agentweb.app.workbench.run.WorkbenchStageRunSubmissionResult;
import com.example.agentweb.app.workbench.stage.WorkbenchStageCommandQueryService;
import com.example.agentweb.app.workbench.stage.WorkbenchStageCommandView;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.interfaces.workbench.dto.SubmitWorkbenchRunRequest;
import com.example.agentweb.interfaces.workbench.dto.WorkbenchRunAttachmentRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workbench Run 202、Header 转换与 SSE cursor/headers 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunControllerTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T10:00:00Z");

    private WorkbenchRunAppService appService;
    private WorkbenchStageRunAppService stageRunAppService;
    private WorkbenchRunHistoryAppService historyAppService;
    private CurrentUserProvider currentUserProvider;
    private WorkbenchStageCommandQueryService stageCommandQueryService;
    private WorkbenchRunController controller;

    @BeforeEach
    void setUp() {
        appService = mock(WorkbenchRunAppService.class);
        stageRunAppService = mock(WorkbenchStageRunAppService.class);
        historyAppService = mock(WorkbenchRunHistoryAppService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        stageCommandQueryService = mock(
                WorkbenchStageCommandQueryService.class);
        when(currentUserProvider.currentUserId()).thenReturn("owner-1");
        when(currentUserProvider.currentUserName()).thenReturn("Alex");
        controller = new WorkbenchRunController(
                appService, stageRunAppService, historyAppService,
                currentUserProvider, stageCommandQueryService);
    }

    @Test
    void listStageCommandsShouldReturnFrozenCommandsFromOwnerStage() {
        WorkbenchStageCommandView command = mock(
                WorkbenchStageCommandView.class);
        when(command.getIdentifier()).thenReturn("design");
        when(command.getDescription()).thenReturn("Design a solution");
        when(command.getArgumentHint()).thenReturn("<topic>");
        when(stageCommandQueryService.list(
                any(), eq(WorkbenchId.of("wb-1")), eq("stage-1")))
                .thenReturn(Collections.singletonList(command));

        var result = controller.listStageCommands("wb-1", "stage-1");

        assertEquals(1, result.size());
        assertEquals("design", result.get(0).getName());
        assertEquals("Design a solution", result.get(0).getDescription());
        verify(stageCommandQueryService).list(
                any(), eq(WorkbenchId.of("wb-1")), eq("stage-1"));
    }

    @Test
    void submitShouldMapHeadersAndReturnAcceptedLocation() {
        WorkbenchStageRunSubmissionResult result = mock(
                WorkbenchStageRunSubmissionResult.class);
        when(result.getRunId()).thenReturn("run-1");
        when(stageRunAppService.submit(any(), any())).thenReturn(result);
        SubmitWorkbenchRunRequest request = new SubmitWorkbenchRunRequest();
        request.setMessage("design the solution");
        request.setRunMode("DISCUSS_READ_ONLY");
        request.setAttachments(Collections.emptyList());

        ResponseEntity<WorkbenchStageRunSubmissionResult> response =
                controller.submit(
                        "workbench-1", "stage-1",
                        "submission-1", 7L, request);

        assertEquals(202, response.getStatusCode().value());
        assertEquals("/api/workbenches/workbench-1/runs/run-1",
                response.getHeaders().getLocation().toString());
        ArgumentCaptor<OwnerReference> actor =
                ArgumentCaptor.forClass(OwnerReference.class);
        ArgumentCaptor<SubmitWorkbenchStageRunCommand> command =
                ArgumentCaptor.forClass(
                        SubmitWorkbenchStageRunCommand.class);
        verify(stageRunAppService).submit(
                actor.capture(), command.capture());
        assertEquals("owner-1", actor.getValue().getOwnerId());
        assertEquals("stage-1",
                command.getValue().getStageInstanceIdentifier());
        assertEquals(RunMode.DISCUSS_READ_ONLY,
                command.getValue().getRunMode());
        assertEquals(7L, command.getValue().getExpectedVersion());
        assertEquals("submission-1",
                command.getValue().getIdempotencyKey());
    }

    @Test
    void submitRequestShouldRejectMoreThanEightAttachmentsAtHttpBoundary() {
        SubmitWorkbenchRunRequest request = new SubmitWorkbenchRunRequest();
        request.setMessage("design the solution");
        request.setRunMode("DISCUSS_READ_ONLY");
        List<WorkbenchRunAttachmentRequest> attachments =
                new ArrayList<WorkbenchRunAttachmentRequest>();
        for (int index = 0; index < 9; index++) {
            WorkbenchRunAttachmentRequest attachment =
                    new WorkbenchRunAttachmentRequest();
            attachment.setRepositoryKey("agent-web");
            attachment.setRelativePath("docs/attachment-" + index + ".md");
            attachment.setContentHash(String.join(
                    "", Collections.nCopies(64, "a")));
            attachments.add(attachment);
        }
        request.setAttachments(attachments);

        try (ValidatorFactory factory =
                     Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertEquals(1, validator.validate(request).stream()
                    .filter(violation -> "attachments".equals(
                            violation.getPropertyPath().toString()))
                    .count());
        }
    }

    @Test
    void stopShouldReturnAcceptedDomainDecision() {
        ChatRun run = pendingRun();
        WorkbenchRunStopResult result =
                WorkbenchRunStopResult.from(run);
        when(appService.stop(any(), any(), eq("run-1")))
                .thenReturn(result);

        ResponseEntity<WorkbenchRunStopResult> response =
                controller.stop("workbench-1", "run-1");

        assertEquals(202, response.getStatusCode().value());
        assertEquals("run-1", response.getBody().getRunId());
    }

    @Test
    void listShouldMapOptionalStageAndStableCursor() {
        when(historyAppService.list(any(), any(), any()))
                .thenReturn(new WorkbenchRunListPage(
                        Collections.emptyList(), null));

        controller.list(
                "workbench-1", "stage-1",
                Long.valueOf(100L), "run-9", 30);

        ArgumentCaptor<WorkbenchRunListRequest> request =
                ArgumentCaptor.forClass(WorkbenchRunListRequest.class);
        verify(historyAppService).list(
                any(), eq(WorkbenchId.of("workbench-1")),
                request.capture());
        assertEquals("stage-1",
                request.getValue().getStageInstanceIdentifier());
        assertEquals(100L,
                request.getValue().getCursor().getCreatedAt());
        assertEquals("run-9",
                request.getValue().getCursor().getRunId());
        assertEquals(30, request.getValue().getLimit());
    }

    @Test
    void partialListCursorShouldFailBeforeApplicationCall() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.list(
                        "workbench-1", null,
                        Long.valueOf(100L), null, 20));

        verifyNoInteractions(historyAppService);
    }

    @Test
    void eventPageShouldMapBoundedCursorRequest() {
        controller.eventPage("workbench-1", "run-1", 7L, 250);

        ArgumentCaptor<WorkbenchRunEventPageRequest> request =
                ArgumentCaptor.forClass(WorkbenchRunEventPageRequest.class);
        verify(historyAppService).events(
                any(), eq(WorkbenchId.of("workbench-1")),
                eq("run-1"), request.capture());
        assertEquals(7L, request.getValue().getAfter());
        assertEquals(250, request.getValue().getLimit());
    }

    @Test
    void eventsShouldPreferLastEventIdAndReturn410WithProxyHeaders()
            throws Exception {
        when(appService.subscribe(
                any(), any(), eq("run-1"), eq(9L), any()))
                .thenThrow(new WorkbenchRunCursorExpiredException(
                        "run-1", 10L, 20L, "cursor expired"));
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        assertNull(controller.events(
                "workbench-1", "run-1", "9", 5L, response));

        assertEquals(410, response.getStatus());
        assertEquals("no-cache, no-transform",
                response.getHeader("Cache-Control"));
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        org.junit.jupiter.api.Assertions.assertTrue(
                response.getContentAsString().contains(
                        "WORKBENCH_RUN_CURSOR_EXPIRED"));
        verify(appService).subscribe(
                any(), eq(WorkbenchId.of("workbench-1")),
                eq("run-1"), eq(9L), any());
    }

    @Test
    void malformedLastEventIdShouldFailBeforeApplicationCall() {
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        assertThrows(IllegalArgumentException.class,
                () -> controller.events(
                        "workbench-1", "run-1", "abc", null,
                        response));
    }

    @Test
    void pagedEventCursorExpiryShouldUseStable410Body() {
        ResponseEntity<java.util.Map<String, Object>> response =
                new WorkbenchExceptionHandler()
                        .handleWorkbenchRunCursorExpired(
                                new WorkbenchRunCursorExpiredException(
                                        "run-1", 10L, 20L,
                                        "workbench run event cursor expired"));

        assertEquals(410, response.getStatusCode().value());
        assertEquals("WORKBENCH_RUN_CURSOR_EXPIRED",
                response.getBody().get("code"));
        assertEquals(10L,
                response.getBody().get("earliestRetainedSeq"));
        assertEquals(20L,
                response.getBody().get("lastEventSeq"));
    }

    @Test
    void runtimePreflightFailureShouldUseSanitizedRunUnavailableContract() {
        RuntimePreflightException failure = new RuntimePreflightException(
                RuntimePreflightErrorCode.RUNTIME_PROBE_START_FAILED,
                "probe failed at /home/private/workspace",
                new IOException("secret-provider-token"));

        ResponseEntity<Map<String, Object>> response =
                new WorkbenchExceptionHandler()
                        .handleRuntimePreflight(failure);

        assertEquals(503, response.getStatusCode().value());
        assertEquals("WORKBENCH_RUN_UNAVAILABLE",
                response.getBody().get("code"));
        assertEquals("workbench run service is unavailable",
                response.getBody().get("message"));
        assertFalse(response.getBody().toString().contains("/home/private"));
        assertFalse(response.getBody().toString().contains("secret-provider"));
    }

    @Test
    void brokenPipeShouldCloseSubscriptionWithoutEscapingAsBusinessFailure()
            throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        WorkbenchRunStreamHandle handle =
                mock(WorkbenchRunStreamHandle.class);
        when(appService.subscribe(any(), any(), eq("run-1"), eq(0L), any()))
                .thenReturn(handle);
        controller = new WorkbenchRunController(
                appService, stageRunAppService, historyAppService,
                currentUserProvider, stageCommandQueryService) {
            @Override
            SseEmitter createEmitter() {
                return emitter;
            }
        };
        MockHttpServletResponse response =
                new MockHttpServletResponse();
        ArgumentCaptor<WorkbenchRunStreamSink> sink =
                ArgumentCaptor.forClass(WorkbenchRunStreamSink.class);

        controller.events(
                "workbench-1", "run-1", null, null, response);
        verify(appService).subscribe(
                any(), any(), eq("run-1"), eq(0L), sink.capture());
        doThrow(new IOException("Broken pipe")).when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        WorkbenchRunEvent event = mock(WorkbenchRunEvent.class);
        when(event.getSequence()).thenReturn(1L);
        when(event.getEventType()).thenReturn("agent_chunk");
        when(event.getPayload()).thenReturn("{}");

        assertDoesNotThrow(() -> sink.getValue().send(event));
        assertDoesNotThrow(() -> sink.getValue().send(event));
        verify(emitter, times(1))
                .send(any(SseEmitter.SseEventBuilder.class));
        verify(handle, times(1)).close();
    }

    private ChatRun pendingRun() {
        return ChatRun.submit(
                ChatRunId.of("run-1"), "session-1", 1L,
                "submission-1", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:stage-1", "run-1"), NOW);
    }
}
