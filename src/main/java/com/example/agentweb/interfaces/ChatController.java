package com.example.agentweb.interfaces;

import com.example.agentweb.app.ChatAppService;
import com.example.agentweb.domain.ChatMessage;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.SessionRepository;
import com.example.agentweb.domain.SlashCommand;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(path = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class ChatController {

    private final ChatAppService appService;
    private final SessionRepository sessionRepository;

    public ChatController(ChatAppService appService, SessionRepository sessionRepository) {
        this.appService = appService;
        this.sessionRepository = sessionRepository;
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

    @GetMapping(value = "/session/{id}/message/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@PathVariable("id") String id,
                            @RequestParam("message") String message,
                            @RequestParam(value = "resumeId", required = false) String resumeId,
                            @RequestParam(value = "env", required = false) String env) {
        return appService.streamMessage(id, message, resumeId, env);
    }

    @GetMapping("/session/{id}/commands")
    public List<Map<String, Object>> listCommands(@PathVariable("id") String id) {
        return appService.listCommands(id).stream()
                .map(this::toCommandDto)
                .collect(java.util.stream.Collectors.toList());
    }

    private Map<String, Object> toCommandDto(SlashCommand cmd) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("name", cmd.getName());
        m.put("description", cmd.getDescription());
        m.put("argumentHint", cmd.getArgumentHint());
        return m;
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> listSessions(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        int offset = (page - 1) * size;
        return sessionRepository.findSummaryPaged(offset, size);
    }

    @GetMapping("/session/{id}/messages")
    public List<Map<String, Object>> getMessages(@PathVariable("id") String id) {
        ChatSession s = sessionRepository.findById(id);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + id);
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ChatMessage msg : s.getMessages()) {
            Map<String, Object> m = new HashMap<String, Object>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            m.put("timestamp", msg.getTimestamp().toString());
            result.add(m);
        }
        return result;
    }

    @DeleteMapping("/session/{id}")
    public Map<String, Object> deleteSession(@PathVariable("id") String id) {
        sessionRepository.deleteById(id);
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("success", true);
        return m;
    }

    @PostMapping("/session/{id}/stop")
    public Map<String, Object> stop(@PathVariable("id") String id) {
        appService.stopSession(id);
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("success", true);
        return m;
    }
}
