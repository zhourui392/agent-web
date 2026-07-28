package com.example.agentweb.interfaces;

import com.example.agentweb.app.chatrun.ToolInvocationQueryService;
import com.example.agentweb.domain.chatrun.ToolInvocation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(path = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
public class ToolInvocationController {
    private final ToolInvocationQueryService queryService;

    public ToolInvocationController(ToolInvocationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/session/{sessionId}/tool-invocations")
    public List<ToolInvocation> bySession(@PathVariable("sessionId") String sessionId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return queryService.findBySession(sessionId, limit, offset);
    }

    @GetMapping("/runs/{runId}/tool-invocations")
    public List<ToolInvocation> byRun(@PathVariable("runId") String runId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return queryService.findByRun(runId, limit, offset);
    }
}
