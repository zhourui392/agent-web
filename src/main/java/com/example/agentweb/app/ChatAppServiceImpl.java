package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.AgentType;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.infra.InMemorySessionRepo;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;

@Service
public class ChatAppServiceImpl implements ChatAppService {

    private final InMemorySessionRepo repo;
    private final AgentGateway gateway;
    private final Executor agentExecutor;

    public ChatAppServiceImpl(InMemorySessionRepo repo, AgentGateway gateway, Executor agentExecutor) {
        this.repo = repo;
        this.gateway = gateway;
        this.agentExecutor = agentExecutor;
    }

    @Override
    public ChatSession startSession(StartSessionRequest req) {
        Assert.notNull(req, "request is null");
        AgentType type = AgentType.valueOf(req.getAgentType());
        File dir = new File(req.getWorkingDir());
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Working directory not found: " + req.getWorkingDir());
        }
        ChatSession s = new ChatSession(type, dir.getAbsolutePath());
        repo.save(s);
        return s;
    }

    @Override
    public String sendMessage(String sessionId, SendMessageRequest req) throws IOException, InterruptedException {
        ChatSession s = getSession(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return gateway.runOnce(s.getAgentType(), s.getWorkingDir(), req.getMessage());
    }

    @Override
    public ChatSession getSession(String sessionId) {
        return repo.find(sessionId);
    }

    @Override
    public SseEmitter streamMessage(String sessionId, String message, String resumeId) {
        final ChatSession s = getSession(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        // default timeout: backend process timeout + buffer; using 5 minutes if unknown
        final long timeoutMs = 5L * 60L * 1000L;
        final SseEmitter emitter = new SseEmitter(timeoutMs);

        agentExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    gateway.runStream(s.getAgentType(), s.getWorkingDir(), message, resumeId,
                            new java.util.function.Consumer<String>() {
                                @Override
                                public void accept(String chunk) {
                                    try {
                                        emitter.send(SseEmitter.event().name("chunk").data(chunk));
                                    } catch (Exception e) {
                                        // client likely disconnected; best effort to stop
                                    }
                                }
                            },
                            new java.util.function.IntConsumer() {
                                @Override
                                public void accept(int code) {
                                    try {
                                        emitter.send(SseEmitter.event().name("exit").data(code));
                                    } catch (Exception ignore) {
                                        // ignore
                                    }
                                    emitter.complete();
                                }
                            });
                } catch (Exception ex) {
                    try {
                        emitter.send(SseEmitter.event().name("error").data(ex.getMessage()));
                    } catch (Exception ignore) { /* ignore */ }
                    emitter.completeWithError(ex);
                }
            }
        });
        return emitter;
    }
}
