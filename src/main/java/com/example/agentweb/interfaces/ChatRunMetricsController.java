package com.example.agentweb.interfaces;

import com.example.agentweb.app.chatrun.ChatRunDiagnosticView;
import com.example.agentweb.app.chatrun.ChatRunMetricsOverview;
import com.example.agentweb.app.chatrun.ChatRunMetricsQueryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台可恢复聊天流指标 / 诊断端点。路径 {@code /api/metrics/chat-run/**} 落在
 * {@code /api/metrics} 前缀内,复用 {@code AdminAuthFilter} 口令把关,未登录返回 401。
 * 控制器只做委托与参数 clamp,不含业务逻辑。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@RestController
@RequestMapping(path = "/api/metrics/chat-run", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatRunMetricsController {

    private static final int DEFAULT_RUN_LIMIT = 50;
    private static final int MIN_RUN_LIMIT = 1;
    private static final int MAX_RUN_LIMIT = 200;

    private final ChatRunMetricsQueryService queryService;

    public ChatRunMetricsController(ChatRunMetricsQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/overview")
    public ResponseEntity<ChatRunMetricsOverview> overview() {
        return ResponseEntity.ok(queryService.overview());
    }

    @GetMapping("/runs")
    public ResponseEntity<List<ChatRunDiagnosticView>> runs(
            @RequestParam(value = "limit", defaultValue = "" + DEFAULT_RUN_LIMIT) int limit) {
        int safeLimit = Math.min(Math.max(limit, MIN_RUN_LIMIT), MAX_RUN_LIMIT);
        return ResponseEntity.ok(queryService.recentRuns(safeLimit));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<ChatRunDiagnosticView> run(@PathVariable String runId) {
        return queryService.diagnose(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
