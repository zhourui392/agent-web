package com.example.agentweb.interfaces;

import com.example.agentweb.app.knowledge.KnowledgeInboxAppService;
import com.example.agentweb.app.knowledge.KnowledgeInboxQueryService;
import com.example.agentweb.app.knowledge.KnowledgeSuggestionView;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 知识收件箱 REST（M4）：列表 / 编辑草稿 / 审批（落盘 issue-log）/ 拒绝。
 * actor 一律取当前登录用户（对齐需求线 Controller 约定，不信请求体）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@RestController
@RequestMapping("/api/knowledge-suggestions")
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class KnowledgeInboxController {

    private static final int MAX_PAGE_SIZE = 200;

    private final KnowledgeInboxAppService inboxAppService;
    private final KnowledgeInboxQueryService queryService;
    private final CurrentUserProvider currentUserProvider;

    public KnowledgeInboxController(KnowledgeInboxAppService inboxAppService,
                                    KnowledgeInboxQueryService queryService,
                                    CurrentUserProvider currentUserProvider) {
        this.inboxAppService = inboxAppService;
        this.queryService = queryService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    public List<KnowledgeSuggestionView> list(
            @RequestParam(name = "status", defaultValue = "PENDING") String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return queryService.listByStatus(status, Math.min(Math.max(limit, 1), MAX_PAGE_SIZE));
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable("id") String id) {
        KnowledgeSuggestion approved = inboxAppService.approve(id, actor());
        Map<String, Object> body = new HashMap<>(4);
        body.put("issueId", approved.getIssueId());
        body.put("issuePath", approved.getIssuePath());
        return body;
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable("id") String id,
                                       @RequestBody RejectRequest request) {
        inboxAppService.reject(id, actor(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> revise(@PathVariable("id") String id,
                                       @RequestBody ReviseRequest request) {
        inboxAppService.revise(id, request.title(), request.triggerSignals(),
                request.phenomenon(), request.rootCause(), request.solution(), request.notes());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> onNotFound(NoSuchElementException e) {
        return errorBody(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> onConflict(IllegalStateException e) {
        return errorBody(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onInvalid(IllegalArgumentException e) {
        return errorBody(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>(4);
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }

    private String actor() {
        return currentUserProvider.currentUserId();
    }

    public record RejectRequest(String reason) {
    }

    public record ReviseRequest(String title, List<String> triggerSignals, String phenomenon,
                                String rootCause, String solution, String notes) {
    }
}
