package com.example.agentweb.interfaces;

import com.example.agentweb.app.metrics.RecallAttemptDetail;
import com.example.agentweb.app.metrics.RecallAttemptPage;
import com.example.agentweb.app.metrics.RecallChunkStat;
import com.example.agentweb.app.metrics.RecallMetricsFilter;
import com.example.agentweb.app.metrics.RecallMetricsQueryService;
import com.example.agentweb.app.metrics.RecallMetricsSummary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recall observability metrics endpoints under the existing protected metrics prefix.
 *
 * @author codex
 * @since 2026-06-12
 */
@RestController
@RequestMapping(path = "/api/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
public class RecallMetricsController {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final int DEFAULT_CHUNK_LIMIT = 50;
    private static final int MAX_CHUNK_LIMIT = 50;

    private final RecallMetricsQueryService queryService;

    public RecallMetricsController(RecallMetricsQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/recall")
    public ResponseEntity<RecallMetricsSummary> summary(
            @RequestParam(value = "from", required = false) Long from,
            @RequestParam(value = "to", required = false) Long to) {
        return ResponseEntity.ok(queryService.summary(RecallMetricsFilter.timeRange(from, to)));
    }

    @GetMapping("/recall-attempts")
    public ResponseEntity<RecallAttemptPage> attempts(
            @RequestParam(value = "page", defaultValue = "" + DEFAULT_PAGE) int page,
            @RequestParam(value = "size", defaultValue = "" + DEFAULT_SIZE) int size,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "from", required = false) Long from,
            @RequestParam(value = "to", required = false) Long to,
            @RequestParam(value = "embeddingModel", required = false) String embeddingModel,
            @RequestParam(value = "env", required = false) String env,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "tier", required = false) String tier) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_SIZE);
        return ResponseEntity.ok(queryService.listAttempts(safePage, safeSize, new RecallMetricsFilter(
                status, sessionId, from, to, embeddingModel, env, sourceType, tier)));
    }

    @GetMapping("/recall-attempts/{id}")
    public ResponseEntity<RecallAttemptDetail> detail(@PathVariable("id") String id) {
        RecallAttemptDetail detail = queryService.detail(id);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    @GetMapping("/recall-attempts/by-message/{messageId}")
    public ResponseEntity<RecallAttemptDetail> detailByMessage(@PathVariable("messageId") long messageId) {
        RecallAttemptDetail detail = queryService.detailByMessageId(messageId);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    @GetMapping("/recall-chunks")
    public ResponseEntity<List<RecallChunkStat>> chunks(
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_CHUNK_LIMIT) int limit,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "from", required = false) Long from,
            @RequestParam(value = "to", required = false) Long to,
            @RequestParam(value = "embeddingModel", required = false) String embeddingModel,
            @RequestParam(value = "env", required = false) String env,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "tier", required = false) String tier) {
        int safeLimit = Math.min(Math.max(1, limit), MAX_CHUNK_LIMIT);
        return ResponseEntity.ok(queryService.topChunks(safeLimit, new RecallMetricsFilter(
                status, sessionId, from, to, embeddingModel, env, sourceType, tier)));
    }
}
