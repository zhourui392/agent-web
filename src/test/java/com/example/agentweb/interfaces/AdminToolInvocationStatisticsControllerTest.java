package com.example.agentweb.interfaces;

import com.example.agentweb.app.chatrun.ToolInvocationStatisticsQueryService;
import com.example.agentweb.infra.auth.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author alex
 */
@WebMvcTest(AdminToolInvocationStatisticsController.class)
@Import(GlobalExceptionHandler.class)
class AdminToolInvocationStatisticsControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private ToolInvocationStatisticsQueryService service;
    @MockBean private AuthProperties authProperties;
    @MockBean private com.example.agentweb.app.auth.AuthAppService authAppService;
    @MockBean private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;
    @MockBean private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    @Test void overview_shouldConvertFiltersAndSerializeMetrics() throws Exception {
        ToolInvocationStatisticsQueryService.Overview value=ToolInvocationStatisticsQueryService.Overview.builder()
                .invocationCount(10).conversationCount(2).failedCount(1).failureRate(.1).build();
        when(service.overview(argThat(f -> Long.valueOf(100L).equals(f.getStartedAfter())
                && Long.valueOf(200L).equals(f.getStartedBefore()) && "CLAUDE".equals(f.getProvider())
                && "HISTORY_MIGRATION".equals(f.getSource())))).thenReturn(value);
        mvc.perform(get("/api/admin-tool-invocation-statistics/overview").param("startedAfter","100")
                        .param("startedBefore","200").param("provider","CLAUDE").param("source","HISTORY_MIGRATION"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invocationCount").value(10))
                .andExpect(jsonPath("$.conversationCount").value(2)).andExpect(jsonPath("$.failureRate").value(.1));
    }

    @Test void rankings_shouldClampPaginationAndConvertEnums() throws Exception {
        ToolInvocationStatisticsQueryService.Page<ToolInvocationStatisticsQueryService.RankingRow> page =
                new ToolInvocationStatisticsQueryService.Page<>(Collections.emptyList(),0,1,100);
        when(service.rankings(argThat(f -> "FAILED".equals(f.getStatus())),
                eq(ToolInvocationStatisticsQueryService.RankingType.SKILL),
                eq(ToolInvocationStatisticsQueryService.RankingOrder.FAILED_COUNT_DESC),eq(1),eq(100))).thenReturn(page);
        mvc.perform(get("/api/admin-tool-invocation-statistics/rankings").param("type","SKILL")
                        .param("order","FAILED_COUNT_DESC").param("status","FAILED").param("page","0").param("size","999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(100));
        verify(service).rankings(argThat(f -> "FAILED".equals(f.getStatus())),
                eq(ToolInvocationStatisticsQueryService.RankingType.SKILL),
                eq(ToolInvocationStatisticsQueryService.RankingOrder.FAILED_COUNT_DESC),eq(1),eq(100));
    }

    @Test void invalidTimeAndEnum_shouldReturnBadRequest() throws Exception {
        mvc.perform(get("/api/admin-tool-invocation-statistics/daily-trend")
                        .param("startedAfter","200").param("startedBefore","100"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin-tool-invocation-statistics/rankings").param("type","INVALID"))
                .andExpect(status().isBadRequest());
    }
}
