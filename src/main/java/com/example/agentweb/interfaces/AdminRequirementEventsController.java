package com.example.agentweb.interfaces;

import com.example.agentweb.app.requirement.RequirementEventSearchItem;
import com.example.agentweb.app.requirement.RequirementQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * admin 需求事件检索（detailed-design §0.5 排查入口③）：按 actor / 时间段过滤，
 * 鉴权走 AdminAuthFilter 管理口令（前缀已加入 protected-prefixes，普通会话过滤器放行以防双重拦截）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@RestController
@RequestMapping(path = "/api/admin-requirement-events", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class AdminRequirementEventsController {

    private static final int MAX_LIMIT = 500;

    private final RequirementQueryService queryService;

    public AdminRequirementEventsController(RequirementQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public List<RequirementEventSearchItem> search(
            @RequestParam(value = "actor", required = false) String actor,
            @RequestParam(value = "from", required = false) Long fromMillis,
            @RequestParam(value = "to", required = false) Long toMillis,
            @RequestParam(value = "limit", required = false, defaultValue = "200") int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return queryService.searchEvents(actor, fromMillis, toMillis, capped);
    }
}
