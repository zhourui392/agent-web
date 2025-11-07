package com.example.agentweb.interfaces;

import com.example.agentweb.app.ChatAppService;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.validation.Valid;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping(path = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class ChatController {

    private final ChatAppService appService;

    public ChatController(ChatAppService appService) {
        this.appService = appService;
    }

    @PostMapping("/session")
    public Map<String, Object> start(@Valid @RequestBody StartSessionRequest req) {
        ChatSession s = appService.startSession(req);
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("sessionId", s.getId());
        m.put("agentType", s.getAgentType().name());
        m.put("workingDir", s.getWorkingDir());
        return m;
    }

    @PostMapping("/session/{id}/message")
    public Map<String, Object> send(@PathVariable("id") String id, @Valid @RequestBody SendMessageRequest req) throws IOException, InterruptedException {
        String out = appService.sendMessage(id, req);
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("output", out);
        return m;
    }

    @GetMapping(value = "/session/{id}/message/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("id") String id,
                            @RequestParam("message") String message,
                            @RequestParam(value = "resumeId", required = false) String resumeId) {
        return appService.streamMessage(id, message, resumeId);
    }
}
