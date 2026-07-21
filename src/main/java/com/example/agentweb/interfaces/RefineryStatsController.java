package com.example.agentweb.interfaces;

import com.example.agentweb.app.refinery.RefineryStats;
import com.example.agentweb.app.refinery.RefineryStatsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge Refinery 统计指标端点, 供管理后台展示 chunk 分布和诊断 ingest 状态.
 *
 * <p>走 session 鉴权 (AuthFilter), 不走 ApiKeyAuthFilter.
 * 仅在 {@code agent.refinery.enabled=true} 时装配.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-03
 */
@RestController
@RequestMapping(path = "/api/refinery", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "agent.refinery", name = "enabled", havingValue = "true")
public class RefineryStatsController {

    private final RefineryStatsService statsService;

    public RefineryStatsController(RefineryStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/stats")
    public ResponseEntity<RefineryStats> stats() {
        return ResponseEntity.ok(statsService.getStats());
    }
}
