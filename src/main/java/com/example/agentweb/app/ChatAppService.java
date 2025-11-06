package com.example.agentweb.app;

import com.example.agentweb.domain.AgentType;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.StartSessionRequest;

import java.io.IOException;

/**
 * Application service orchestrating session lifecycle and message handling.
 */
public interface ChatAppService {

    ChatSession startSession(StartSessionRequest req);

    /**
     * Send one message to an agent and get consolidated output.
     * Note: Interactive PTY is not supported in v0; the CLI must be invokable non-interactively.
     */
    String sendMessage(String sessionId, SendMessageRequest req) throws IOException, InterruptedException;

    ChatSession getSession(String sessionId);

    /**
     * Stream output using Server-Sent Events.
     */
    org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamMessage(String sessionId, String message);
}
