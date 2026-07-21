package com.example.agentweb.interfaces;

import com.example.agentweb.app.metrics.DailyTrendPoint;
import com.example.agentweb.app.metrics.MetricsOverview;
import com.example.agentweb.app.metrics.MetricsQueryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台指标端点。路径 {@code /api/metrics/**} 由 {@code AdminAuthFilter} 口令把关,
 * 未登录返回 401。控制器仅做委托与参数 clamp,不含业务逻辑。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@RestController
@RequestMapping(path = "/api/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
public class MetricsController {

    private static final int DEFAULT_TREND_DAYS = 30;
    private static final int MIN_TREND_DAYS = 1;
    private static final int MAX_TREND_DAYS = 90;

    private final MetricsQueryService queryService;

    public MetricsController(MetricsQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/overview")
    public ResponseEntity<MetricsOverview> overview() {
        return ResponseEntity.ok(queryService.overview());
    }

    @GetMapping("/trend")
    public ResponseEntity<List<DailyTrendPoint>> trend(
            @RequestParam(value = "days", defaultValue = "" + DEFAULT_TREND_DAYS) int days) {
        int safeDays = Math.min(Math.max(days, MIN_TREND_DAYS), MAX_TREND_DAYS);
        return ResponseEntity.ok(queryService.trend(safeDays));
    }
}
