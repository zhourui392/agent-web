package com.example.agentweb.interfaces;

import com.example.agentweb.app.chatrun.ChatRunDiagnosticView;
import com.example.agentweb.app.chatrun.ChatRunMetricsOverview;
import com.example.agentweb.app.chatrun.ChatRunMetricsQueryService;
import com.example.agentweb.infra.auth.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ChatRun 管理指标接口切片测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@WebMvcTest(ChatRunMetricsController.class)
@Import(GlobalExceptionHandler.class)
class ChatRunMetricsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ChatRunMetricsQueryService queryService;
    @MockBean
    private AuthProperties authProperties;
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;
    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    @Test
    void overview_should_return_persisted_and_live_metrics() throws Exception {
        ChatRunMetricsOverview overview = new ChatRunMetricsOverview();
        overview.setActiveTotal(2L);
        Map<String, Long> activeByStatus = new LinkedHashMap<String, Long>();
        activeByStatus.put("RUNNING", 2L);
        overview.setActiveByStatus(activeByStatus);
        overview.setActiveByAgentType(Collections.<String, Long>emptyMap());
        overview.setTerminalByStatus(Collections.<String, Long>emptyMap());
        overview.setFailureByCode(Collections.<String, Long>emptyMap());
        overview.setEventRows(12L);
        overview.setEventPayloadBytes(1024L);
        overview.setLiveSubscribers(3);
        overview.setSlowConsumerClosedTotal(1L);
        when(queryService.overview()).thenReturn(overview);

        mvc.perform(get("/api/metrics/chat-run/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeTotal").value(2))
                .andExpect(jsonPath("$.activeByStatus.RUNNING").value(2))
                .andExpect(jsonPath("$.eventRows").value(12))
                .andExpect(jsonPath("$.eventPayloadBytes").value(1024))
                .andExpect(jsonPath("$.liveSubscribers").value(3))
                .andExpect(jsonPath("$.slowConsumerClosedTotal").value(1));
    }

    @Test
    void runs_should_use_default_limit() throws Exception {
        when(queryService.recentRuns(50)).thenReturn(Collections.singletonList(view("run-1")));

        mvc.perform(get("/api/metrics/chat-run/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value("run-1"));

        verify(queryService).recentRuns(50);
    }

    @Test
    void runs_should_clamp_limit_to_supported_range() throws Exception {
        when(queryService.recentRuns(1)).thenReturn(Collections.<ChatRunDiagnosticView>emptyList());
        when(queryService.recentRuns(200)).thenReturn(Collections.<ChatRunDiagnosticView>emptyList());

        mvc.perform(get("/api/metrics/chat-run/runs").param("limit", "0"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/metrics/chat-run/runs").param("limit", "999"))
                .andExpect(status().isOk());

        verify(queryService).recentRuns(1);
        verify(queryService).recentRuns(200);
    }

    @Test
    void run_should_return_diagnostic_projection() throws Exception {
        when(queryService.diagnose("run-1")).thenReturn(Optional.of(view("run-1")));

        mvc.perform(get("/api/metrics/chat-run/runs/{runId}", "run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.lastEventSeq").value(2))
                .andExpect(jsonPath("$.eventCount").value(2))
                .andExpect(jsonPath("$.liveSubscribers").value(1))
                .andExpect(jsonPath("$.assistantPersisted").value(true));
    }

    @Test
    void missing_run_should_return_not_found() throws Exception {
        when(queryService.diagnose("missing-run")).thenReturn(Optional.<ChatRunDiagnosticView>empty());

        mvc.perform(get("/api/metrics/chat-run/runs/{runId}", "missing-run"))
                .andExpect(status().isNotFound());
    }

    private ChatRunDiagnosticView view(String runId) {
        return new ChatRunDiagnosticView(runId, "session-1", "RUNNING", "CODEX",
                2L, 2L, Long.valueOf(1200L), 1, Long.valueOf(99L),
                null, null, null, Long.valueOf(1000L), null, 900L);
    }
}
