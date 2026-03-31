package com.example.agentweb.app;

import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.SlashCommand;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

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
     * @param resumeId Optional resume ID for continuing a conversation
     */
    SseEmitter streamMessage(String sessionId, String message, String resumeId, String env);

    /**
     * Stop a running stream for the given session.
     */
    void stopSession(String sessionId);

    List<SlashCommand> listCommands(String sessionId);
}
