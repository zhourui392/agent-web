package com.example.agentweb.interfaces;

import com.example.agentweb.app.metrics.RecallAttemptDetail;
import com.example.agentweb.app.metrics.RecallAttemptListItem;
import com.example.agentweb.app.metrics.RecallAttemptPage;
import com.example.agentweb.app.metrics.RecallChunkStat;
import com.example.agentweb.app.metrics.RecallHitDetail;
import com.example.agentweb.app.metrics.RecallMetricsFilter;
import com.example.agentweb.app.metrics.RecallMetricsQueryService;
import com.example.agentweb.app.metrics.RecallMetricsSummary;
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

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link RecallMetricsController}.
 *
 * @author codex
 * @since 2026-06-12
 */
@WebMvcTest(RecallMetricsController.class)
@Import(GlobalExceptionHandler.class)
class RecallMetricsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RecallMetricsQueryService queryService;
    @MockBean
    private AuthProperties authProperties;

    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;

    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;
    @Test
    void summary_should_return_recall_metrics_and_only_passthrough_time_range() throws Exception {
        RecallMetricsSummary summary = new RecallMetricsSummary();
        summary.setAttemptCount(8);
        summary.setExecutedCount(6);
        summary.setHitCount(3);
        summary.setNoHitCount(2);
        summary.setErrorCount(1);
        summary.setSkippedCount(1);
        summary.setPendingCount(1);
        summary.setQualityHitRate(0.6d);
        summary.setAvgLatencyMs(123.5d);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put("HIT", 3L);
        byStatus.put("NO_HIT", 2L);
        summary.setByStatus(byStatus);
        Map<String, Long> byModel = new LinkedHashMap<>();
        byModel.put("text-embedding-3-small", 5L);
        summary.setByEmbeddingModel(byModel);
        when(queryService.summary(argThat(filter -> filter != null
                && Long.valueOf(1000L).equals(filter.getFrom())
                && Long.valueOf(2000L).equals(filter.getTo())
                && filter.getStatus() == null
                && filter.getEmbeddingModel() == null
                && filter.getEnv() == null
                && filter.getSourceType() == null
                && filter.getTier() == null))).thenReturn(summary);

        mvc.perform(get("/api/metrics/recall")
                        .param("from", "1000")
                        .param("to", "2000")
                        .param("status", "HIT")
                        .param("embeddingModel", "qwen")
                        .param("env", "test")
                        .param("sourceType", "CHAT")
                        .param("tier", "EXPLORATORY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptCount").value(8))
                .andExpect(jsonPath("$.qualityHitRate").value(0.6d))
                .andExpect(jsonPath("$.avgLatencyMs").value(123.5d))
                .andExpect(jsonPath("$.byStatus.HIT").value(3))
                .andExpect(jsonPath("$.byEmbeddingModel.text-embedding-3-small").value(5));

        verify(queryService).summary(argThat(filter -> filter != null
                && Long.valueOf(1000L).equals(filter.getFrom())
                && Long.valueOf(2000L).equals(filter.getTo())
                && filter.getStatus() == null
                && filter.getEmbeddingModel() == null
                && filter.getEnv() == null
                && filter.getSourceType() == null
                && filter.getTier() == null));
    }

    @Test
    void attempts_should_clamp_page_and_size_then_return_page() throws Exception {
        RecallAttemptListItem item = new RecallAttemptListItem();
        item.setId("att-1");
        item.setSessionId("sess-1");
        item.setUserMessageId(11L);
        item.setAssistantMessageId(12L);
        item.setQuerySummary("如何排查召回");
        item.setRecallEnabled(true);
        item.setEnv("test");
        item.setStatus("HIT");
        item.setHitCount(2);
        item.setTopVectorScore(0.91d);
        item.setEmbeddingModel("text-embedding-3-small");
        item.setLatencyMs(88L);
        item.setCreatedAt(1500L);
        RecallAttemptPage page = new RecallAttemptPage();
        page.setPage(1);
        page.setSize(100);
        page.setTotal(1);
        page.setItems(Collections.singletonList(item));
        when(queryService.listAttempts(eq(1), eq(100), argThat(filter -> filter != null
                && "HIT".equals(filter.getStatus())
                && "sess-1".equals(filter.getSessionId())
                && Long.valueOf(1000L).equals(filter.getFrom())
                && Long.valueOf(2000L).equals(filter.getTo())
                && "qwen".equals(filter.getEmbeddingModel())
                && "test".equals(filter.getEnv())
                && "CHAT".equals(filter.getSourceType())
                && "EXPLORATORY".equals(filter.getTier())))).thenReturn(page);

        mvc.perform(get("/api/metrics/recall-attempts")
                        .param("page", "0")
                        .param("size", "999")
                        .param("status", "HIT")
                        .param("sessionId", "sess-1")
                        .param("from", "1000")
                        .param("to", "2000")
                        .param("embeddingModel", "qwen")
                        .param("env", "test")
                        .param("sourceType", "CHAT")
                        .param("tier", "EXPLORATORY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("att-1"))
                .andExpect(jsonPath("$.items[0].recallEnabled").value(true))
                .andExpect(jsonPath("$.items[0].topVectorScore").value(0.91d));

        verify(queryService).listAttempts(eq(1), eq(100), argThat(filter -> filter != null
                && "HIT".equals(filter.getStatus())
                && "sess-1".equals(filter.getSessionId())
                && Long.valueOf(1000L).equals(filter.getFrom())
                && Long.valueOf(2000L).equals(filter.getTo())
                && "qwen".equals(filter.getEmbeddingModel())
                && "test".equals(filter.getEnv())
                && "CHAT".equals(filter.getSourceType())
                && "EXPLORATORY".equals(filter.getTier())));
    }

    @Test
    void detail_should_return_attempt_with_hit_snapshots() throws Exception {
        RecallHitDetail hit = new RecallHitDetail();
        hit.setRankNo(1);
        hit.setChunkId("chunk-1");
        hit.setTitle("历史结论");
        hit.setConclusion("可观测表记录召回事实");
        hit.setFinalScore(0.82d);
        hit.setVectorScore(0.76d);
        hit.setSourceType("CHAT");
        hit.setTier("EXPLORATORY");
        RecallAttemptDetail detail = new RecallAttemptDetail();
        detail.setId("att-1");
        detail.setSessionId("sess-1");
        detail.setQuery("如何排查召回");
        detail.setParamsJson("{\"minVectorScore\":0.6}");
        detail.setStatus("HIT");
        detail.setHitCount(1);
        detail.setHits(Collections.singletonList(hit));
        when(queryService.detail("att-1")).thenReturn(detail);

        mvc.perform(get("/api/metrics/recall-attempts/{id}", "att-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("att-1"))
                .andExpect(jsonPath("$.query").value("如何排查召回"))
                .andExpect(jsonPath("$.paramsJson").value("{\"minVectorScore\":0.6}"))
                .andExpect(jsonPath("$.hits[0].chunkId").value("chunk-1"))
                .andExpect(jsonPath("$.hits[0].finalScore").value(0.82d));
    }

    @Test
    void detailByMessage_should_return_attempt_matched_by_chat_message_id() throws Exception {
        RecallAttemptDetail detail = new RecallAttemptDetail();
        detail.setId("att-1");
        detail.setUserMessageId(11L);
        detail.setAssistantMessageId(12L);
        detail.setStatus("NO_HIT");
        when(queryService.detailByMessageId(12L)).thenReturn(detail);

        mvc.perform(get("/api/metrics/recall-attempts/by-message/{messageId}", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("att-1"))
                .andExpect(jsonPath("$.assistantMessageId").value(12));
    }

    @Test
    void topChunks_should_return_chunk_recall_usage() throws Exception {
        RecallChunkStat stat = new RecallChunkStat();
        stat.setChunkId("chunk-1");
        stat.setRecalledTimes(2L);
        stat.setAvgVectorScore(0.81d);
        stat.setAvgFinalScore(0.78d);
        stat.setTitle("历史结论");
        stat.setSourceType("CHAT");
        stat.setTier("EXPLORATORY");
        when(queryService.topChunks(eq(50), argThat(filter -> filter != null
                && Long.valueOf(1000L).equals(filter.getFrom())
                && Long.valueOf(2000L).equals(filter.getTo())
                && "test".equals(filter.getEnv()))))
                .thenReturn(Collections.singletonList(stat));

        mvc.perform(get("/api/metrics/recall-chunks")
                        .param("limit", "999")
                        .param("from", "1000")
                        .param("to", "2000")
                        .param("env", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chunkId").value("chunk-1"))
                .andExpect(jsonPath("$[0].recalledTimes").value(2))
                .andExpect(jsonPath("$[0].avgVectorScore").value(0.81d));

        verify(queryService).topChunks(eq(50), argThat(filter -> filter != null
                && Long.valueOf(1000L).equals(filter.getFrom())
                && Long.valueOf(2000L).equals(filter.getTo())
                && "test".equals(filter.getEnv())));
    }

    @Test
    void detail_missing_should_return_404() throws Exception {
        when(queryService.detail("missing")).thenReturn(null);

        mvc.perform(get("/api/metrics/recall-attempts/{id}", "missing"))
                .andExpect(status().isNotFound());
    }
}
