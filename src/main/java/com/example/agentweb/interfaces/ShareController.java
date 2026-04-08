package com.example.agentweb.interfaces;

import com.example.agentweb.domain.ChatMessage;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.SessionRepository;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class ShareController {

    private final SessionRepository sessionRepository;

    public ShareController(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Generate (or return existing) share token for a session.
     */
    @PostMapping(path = "/api/chat/session/{id}/share", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> share(@PathVariable("id") String id) {
        ChatSession session = sessionRepository.findById(id);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + id);
        }
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String actualToken = sessionRepository.setShareToken(id, token);

        Map<String, Object> result = new HashMap<>();
        result.put("shareToken", actualToken);
        return result;
    }

    /**
     * Public endpoint: get shared session messages by token (no auth required).
     */
    @GetMapping(path = "/api/share/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getShared(@PathVariable("token") String token) {
        ChatSession session = sessionRepository.findByShareToken(token);
        if (session == null) {
            throw new IllegalArgumentException("Shared session not found");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("title", session.getTitle());
        result.put("agentType", session.getAgentType().name());
        result.put("workingDir", session.getWorkingDir());
        result.put("createdAt", session.getCreatedAt().toString());

        List<Map<String, Object>> msgs = new ArrayList<>();
        for (ChatMessage msg : session.getMessages()) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            m.put("timestamp", msg.getTimestamp().toString());
            msgs.add(m);
        }
        result.put("messages", msgs);
        return result;
    }
}
