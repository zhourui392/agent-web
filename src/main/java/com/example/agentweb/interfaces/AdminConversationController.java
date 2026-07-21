package com.example.agentweb.interfaces;

import com.example.agentweb.app.metrics.ConversationDetail;
import com.example.agentweb.app.metrics.ConversationPage;
import com.example.agentweb.app.metrics.ConversationQueryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台「所有用户的对话记录」端点。路径 {@code /api/metrics/conversations} 落在
 * {@code /api/metrics} 前缀内,由 {@code AdminAuthFilter} 口令把关,未登录返回 401。
 *
 * <p>控制器只做参数 clamp 与委托,无业务逻辑;全量投影由 {@link ConversationQueryService} 承担。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@RestController
@RequestMapping(path = "/api/metrics/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminConversationController {

    private static final int MIN_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    private final ConversationQueryService queryService;

    public AdminConversationController(ConversationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ResponseEntity<ConversationPage> list(
            @RequestParam(value = "page", defaultValue = "" + MIN_PAGE) int page,
            @RequestParam(value = "size", defaultValue = "" + DEFAULT_SIZE) int size,
            @RequestParam(value = "keyword", required = false) String keyword) {
        int safePage = Math.max(page, MIN_PAGE);
        int safeSize = Math.min(Math.max(size, MIN_SIZE), MAX_SIZE);
        return ResponseEntity.ok(queryService.list(safePage, safeSize, keyword));
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<ConversationDetail> detail(@PathVariable("sessionId") String sessionId) {
        ConversationDetail detail = queryService.detail(sessionId);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }
}
