package com.example.agentweb.interfaces;

import com.example.agentweb.app.requirement.RequirementAppService;
import com.example.agentweb.app.requirement.RequirementBoardItem;
import com.example.agentweb.app.requirement.RequirementDetail;
import com.example.agentweb.app.requirement.RequirementEventView;
import com.example.agentweb.app.requirement.RequirementQueryService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.requirement.RequirementNotFoundException;
import com.example.agentweb.domain.requirement.RequirementSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求线 REST（detailed-design §1.5）。actor 一律取 UserContext，不信任请求体——
 * 这是 T4 人审门"入口收口"的一半：approve 只有此处一个入口，编排/回调无调用路径。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@RestController
@RequestMapping(path = "/api/requirements", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class RequirementController {

    private final RequirementAppService appService;
    private final RequirementQueryService queryService;
    private final CurrentUserProvider currentUserProvider;

    public RequirementController(RequirementAppService appService,
                                 RequirementQueryService queryService,
                                 CurrentUserProvider currentUserProvider) {
        this.appService = appService;
        this.queryService = queryService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> req) {
        String id = appService.create(req.get("title"), req.get("description"),
                currentUserProvider.currentUserId(), RequirementSource.BOARD);
        Map<String, Object> body = new HashMap<>(4);
        body.put("id", id);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    public List<RequirementBoardItem> list(@RequestParam(value = "status", required = false) String status,
                                           @RequestParam(value = "owner", required = false) String owner) {
        return queryService.listBoard(status, owner);
    }

    @GetMapping("/{id}")
    public RequirementDetail detail(@PathVariable("id") String id) {
        RequirementDetail detail = queryService.getDetail(id);
        if (detail == null) {
            throw new RequirementNotFoundException(id);
        }
        return detail;
    }

    @GetMapping("/{id}/events")
    public List<RequirementEventView> events(@PathVariable("id") String id) {
        return queryService.listEvents(id);
    }

    @PostMapping("/{id}/plan")
    public Map<String, Object> attachPlan(@PathVariable("id") String id,
                                          @RequestBody Map<String, String> req) {
        appService.attachPlan(id, req.get("planText"), actor());
        return ok();
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable("id") String id) {
        appService.approve(id, actor());
        return ok();
    }

    @PostMapping("/{id}/reject-plan")
    public Map<String, Object> rejectPlan(@PathVariable("id") String id,
                                          @RequestBody Map<String, String> req) {
        appService.rejectPlan(id, actor(), req.get("reason"));
        return ok();
    }

    @PostMapping("/{id}/suspend")
    public Map<String, Object> suspend(@PathVariable("id") String id,
                                       @RequestBody Map<String, String> req) {
        appService.suspend(id, actor(), req.get("reason"));
        return ok();
    }

    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable("id") String id) {
        appService.resume(id, actor());
        return ok();
    }

    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(@PathVariable("id") String id,
                                       @RequestBody(required = false) Map<String, String> req) {
        appService.archive(id, actor(), req == null ? null : req.get("reason"));
        return ok();
    }

    @PostMapping("/{id}/start-implement")
    public Map<String, Object> startImplement(@PathVariable("id") String id) {
        appService.startImplement(id, actor());
        return ok();
    }

    @PostMapping("/{id}/start-verify")
    public Map<String, Object> startVerify(@PathVariable("id") String id) {
        appService.startVerify(id, actor());
        return ok();
    }

    @PostMapping("/{id}/deliver")
    public Map<String, Object> deliver(@PathVariable("id") String id,
                                       @RequestBody(required = false) Map<String, String> req) {
        appService.markDelivered(id, actor(), req == null ? null : req.get("mrRef"));
        return ok();
    }

    @PostMapping("/{id}/request-changes")
    public Map<String, Object> requestChanges(@PathVariable("id") String id,
                                              @RequestBody Map<String, String> req) {
        appService.requestChanges(id, actor(), req.get("reason"));
        return ok();
    }

    private String actor() {
        return currentUserProvider.currentUserId();
    }

    private Map<String, Object> ok() {
        Map<String, Object> body = new HashMap<>(4);
        body.put("success", true);
        return body;
    }
}
