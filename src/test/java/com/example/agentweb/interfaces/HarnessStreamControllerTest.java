package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.HarnessEventCursorExpiredException;
import com.example.agentweb.app.harness.HarnessRunStreamHandle;
import com.example.agentweb.app.harness.HarnessRunStreamSink;
import com.example.agentweb.app.harness.HarnessSubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link HarnessStreamController} 切片测试：SSE 端点路径、header、cursor 解析、410 过期。
 *
 * @author zhourui(V33215020)
 */
@ExtendWith(SpringExtension.class)
@WebMvcTest(HarnessStreamController.class)
@TestPropertySource(properties = "agent.harness.enabled=true")
class HarnessStreamControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private HarnessSubscriptionService subscriptionService;

    @Test
    void stream_should_prefer_last_event_id_header_over_query_cursor() throws Exception {
        HarnessRunStreamHandle handle = mock(HarnessRunStreamHandle.class);
        when(subscriptionService.subscribe(eq("run-1"), eq(12L), any(HarnessRunStreamSink.class)))
                .thenReturn(handle);

        mvc.perform(get("/api/harness/runs/run-1/stream")
                        .header("Last-Event-ID", "12")
                        .param("after", "3")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"));

        verify(subscriptionService).subscribe(eq("run-1"), eq(12L), any(HarnessRunStreamSink.class));
    }

    @Test
    void stream_should_use_after_param_when_no_last_event_id() throws Exception {
        HarnessRunStreamHandle handle = mock(HarnessRunStreamHandle.class);
        when(subscriptionService.subscribe(eq("run-1"), eq(5L), any(HarnessRunStreamSink.class)))
                .thenReturn(handle);

        mvc.perform(get("/api/harness/runs/run-1/stream")
                        .param("after", "5")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        verify(subscriptionService).subscribe(eq("run-1"), eq(5L), any(HarnessRunStreamSink.class));
    }

    @Test
    void stream_should_default_cursor_to_zero_when_no_cursor_provided() throws Exception {
        HarnessRunStreamHandle handle = mock(HarnessRunStreamHandle.class);
        when(subscriptionService.subscribe(eq("run-1"), eq(0L), any(HarnessRunStreamSink.class)))
                .thenReturn(handle);

        mvc.perform(get("/api/harness/runs/run-1/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        verify(subscriptionService).subscribe(eq("run-1"), eq(0L), any(HarnessRunStreamSink.class));
    }

    @Test
    void expired_cursor_should_return_410_snapshot_metadata() throws Exception {
        when(subscriptionService.subscribe(eq("run-1"), eq(12L), any(HarnessRunStreamSink.class)))
                .thenThrow(new HarnessEventCursorExpiredException("run-1", 500L, 900L));

        mvc.perform(get("/api/harness/runs/run-1/stream")
                        .param("after", "12")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("EVENT_CURSOR_EXPIRED"))
                .andExpect(jsonPath("$.earliestRetainedSeq").value(500))
                .andExpect(jsonPath("$.lastEventSeq").value(900));
    }
}