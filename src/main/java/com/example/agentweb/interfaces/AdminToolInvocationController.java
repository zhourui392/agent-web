package com.example.agentweb.interfaces;

import com.example.agentweb.app.chatrun.ToolInvocationAdminFilter;
import com.example.agentweb.app.chatrun.ToolInvocationAdminQueryService;
import com.example.agentweb.domain.chatrun.ToolInvocation;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping(path = "/api/admin-tool-invocations", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminToolInvocationController {
    private static final int MAX_PAGE_SIZE = 100;
    private final ToolInvocationAdminQueryService queryService;

    public AdminToolInvocationController(ToolInvocationAdminQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ToolInvocationAdminQueryService.ToolInvocationAdminPage list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "invocationKind", required = false) String invocationKind,
            @RequestParam(value = "toolName", required = false) String toolName,
            @RequestParam(value = "skillName", required = false) String skillName,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "triggerSource", required = false) String triggerSource,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "runId", required = false) String runId,
            @RequestParam(value = "startedAfter", required = false) Long startedAfter,
            @RequestParam(value = "startedBefore", required = false) Long startedBefore) {
        return queryService.findPage(ToolInvocationAdminFilter.builder()
                .page(Math.max(1, page)).size(Math.min(MAX_PAGE_SIZE, Math.max(1, size)))
                .provider(provider).invocationKind(invocationKind).toolName(toolName).skillName(skillName)
                .status(status).triggerSource(triggerSource).sessionId(sessionId).runId(runId)
                .startedAfter(startedAfter).startedBefore(startedBefore).build());
    }

    @GetMapping("/overview")
    public Map<String, Long> overview() {
        return queryService.overview();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ToolInvocation> detail(@PathVariable("id") long id) {
        ToolInvocation detail = queryService.findById(id);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }
}
