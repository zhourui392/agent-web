package com.example.agentweb.interfaces;

import com.example.agentweb.app.chatrun.ActiveChatRunView;
import com.example.agentweb.app.chatrun.ChatRunAppService;
import com.example.agentweb.app.chatrun.ChatRunStreamHandle;
import com.example.agentweb.app.chatrun.ChatRunStreamSink;
import com.example.agentweb.app.chatrun.ChatRunSubmission;
import com.example.agentweb.app.chatrun.ChatRunSubscriptionService;
import com.example.agentweb.app.chatrun.ChatRunView;
import com.example.agentweb.app.chatrun.EventCursorExpiredException;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(ChatRunController.class)
@Import(GlobalExceptionHandler.class)
class ChatRunControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ChatRunAppService appService;

    @MockBean
    private ChatRunSubscriptionService subscriptionService;

    @Test
    void submit_should_return_202_location_and_contract() throws Exception {
        ChatRun run = ChatRun.submit(ChatRunId.of("run-1"), "session-1", 11L,
                "key-1", Instant.parse("2026-07-22T10:00:00Z"));
        when(appService.submit(any())).thenReturn(ChatRunSubmission.from(run, false));

        mvc.perform(post("/api/chat/session/session-1/runs")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"question\",\"recall\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/chat/runs/run-1"))
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.duplicated").value(false));
    }

    @Test
    void submit_without_idempotency_key_should_return_400_code() throws Exception {
        mvc.perform(post("/api/chat/session/session-1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"question\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void active_and_stop_should_expose_application_views() throws Exception {
        when(appService.findActive()).thenReturn(Collections.singletonList(
                new ActiveChatRunView("run-1", "session-1", ChatRunStatus.RUNNING,
                        "CODEX", "/workspace", 3L, 1000L, 900L)));
        when(appService.stop("run-1")).thenReturn(mock(ChatRunView.class));

        mvc.perform(get("/api/chat/runs/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value("run-1"));
        mvc.perform(post("/api/chat/runs/run-1/stop"))
                .andExpect(status().isAccepted());
    }

    @Test
    void events_should_prefer_last_event_id_header_over_query_cursor() throws Exception {
        ChatRunStreamHandle handle = mock(ChatRunStreamHandle.class);
        when(subscriptionService.subscribe(eq("run-1"), eq(12L), any(ChatRunStreamSink.class)))
                .thenReturn(handle);

        mvc.perform(get("/api/chat/runs/run-1/events")
                        .header("Last-Event-ID", "12")
                        .param("after", "3")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"));

        verify(subscriptionService).subscribe(eq("run-1"), eq(12L), any(ChatRunStreamSink.class));
    }

    @Test
    void expired_cursor_should_return_410_snapshot_metadata() throws Exception {
        when(subscriptionService.subscribe(eq("run-1"), eq(12L), any(ChatRunStreamSink.class)))
                .thenThrow(new EventCursorExpiredException("run-1", 500L, 900L));

        mvc.perform(get("/api/chat/runs/run-1/events")
                        .param("after", "12")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("EVENT_CURSOR_EXPIRED"))
                .andExpect(jsonPath("$.earliestRetainedSeq").value(500))
                .andExpect(jsonPath("$.lastEventSeq").value(900));
    }
}
