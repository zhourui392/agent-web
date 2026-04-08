package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.AgentType;
import com.example.agentweb.domain.ChatMessage;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.SessionRepository;
import com.example.agentweb.domain.SlashCommand;
import com.example.agentweb.domain.SessionCache;
import com.example.agentweb.domain.SlashCommandExpander;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 聊天应用服务实现，编排会话生命周期、消息收发与对话摘要生成。
 * <p>通过 {@link SessionCache} 做内存快查，{@link SessionRepository} 做持久化，
 * 并委托 {@link AgentGateway} 调用 CLI Agent 完成实际推理。</p>
 */
@Service
public class ChatAppServiceImpl implements ChatAppService {

    private final SessionCache sessionCache;
    private final SessionRepository sessionRepository;
    private final AgentGateway gateway;
    private final Executor agentExecutor;
    private final SlashCommandExpander commandExpander;
    private final IssueLogWriter issueLogWriter;

    public ChatAppServiceImpl(SessionCache sessionCache,
                              SessionRepository sessionRepository,
                              AgentGateway gateway,
                              Executor agentExecutor,
                              SlashCommandExpander commandExpander,
                              IssueLogWriter issueLogWriter) {
        this.sessionCache = sessionCache;
        this.sessionRepository = sessionRepository;
        this.gateway = gateway;
        this.agentExecutor = agentExecutor;
        this.commandExpander = commandExpander;
        this.issueLogWriter = issueLogWriter;
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
        sessionCache.save(s);
        sessionRepository.saveSession(s);
        return s;
    }

    @Override
    public String sendMessage(String sessionId, SendMessageRequest req) throws IOException, InterruptedException {
        ChatSession s = getSession(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        // persist user message
        ChatMessage userMsg = new ChatMessage("user", req.getMessage());
        sessionRepository.addMessage(sessionId, userMsg);

        String output = gateway.runOnce(s.getAgentType(), s.getWorkingDir(), req.getMessage());

        // persist assistant response
        ChatMessage assistantMsg = new ChatMessage("assistant", output);
        sessionRepository.addMessage(sessionId, assistantMsg);

        return output;
    }

    @Override
    public ChatSession getSession(String sessionId) {
        ChatSession s = sessionCache.find(sessionId);
        if (s == null) {
            // Fallback to persistent storage (e.g. after server restart or resuming from history)
            s = sessionRepository.findById(sessionId);
            if (s != null) {
                sessionCache.save(s);
            }
        }
        return s;
    }

    @Override
    public SseEmitter streamMessage(String sessionId, String message, String resumeId, String env) {
        final ChatSession s = getSession(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }

        // persist user message
        ChatMessage userMsg = new ChatMessage("user", message);
        sessionRepository.addMessage(sessionId, userMsg);

        // No SSE timeout – let the CLI process (and its own watchdog) control the lifecycle
        final SseEmitter emitter = new SseEmitter(-1L);
        final StreamChunkHandler handler = new StreamChunkHandler(sessionRepository, sessionId);

        final String envFinal = env;
        agentExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String cliMessage = commandExpander.expandIfCommand(s.getWorkingDir(), message);
                    gateway.runStream(s.getAgentType(), s.getWorkingDir(), cliMessage, sessionId, resumeId, envFinal,
                            handler.onChunk(new java.util.function.Consumer<String>() {
                                @Override
                                public void accept(String chunk) {
                                    try {
                                        emitter.send(SseEmitter.event().name("chunk").data(chunk));
                                    } catch (Exception e) {
                                        // client likely disconnected
                                    }
                                }
                            }),
                            handler.onExit(new java.util.function.IntConsumer() {
                                @Override
                                public void accept(int code) {
                                    try {
                                        emitter.send(SseEmitter.event().name("exit").data(code));
                                    } catch (Exception ignore) {
                                        // ignore
                                    }
                                    emitter.complete();
                                }
                            }));
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

    @Override
    public void stopSession(String sessionId) {
        gateway.stopStream(sessionId);
    }

    @Override
    public boolean isSessionRunning(String sessionId) {
        return gateway.isRunning(sessionId);
    }

    @Override
    public List<SlashCommand> listCommands(String sessionId) {
        ChatSession s = getSession(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return commandExpander.listCommands(s.getWorkingDir());
    }

    @Override
    public Map<String, Object> summarizeSession(String sessionId) throws IOException, InterruptedException {
        ChatSession s = sessionRepository.findById(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }

        // 1. 构建对话文本
        String conversationText = buildConversationText(s);

        // 2. 调用 CLI 生成摘要
        String prompt = buildSummarizePrompt(conversationText);
        String rawOutput = gateway.runOnce(AgentType.CLAUDE, s.getWorkingDir(), prompt);

        // 3. 解析 CLI 输出并写入 issue-log
        String summaryText = issueLogWriter.extractPlainText(rawOutput);
        return issueLogWriter.writeIssueLog(s.getWorkingDir(), sessionId, summaryText);
    }

    private String buildConversationText(ChatSession session) {
        StringBuilder conversation = new StringBuilder();
        for (ChatMessage msg : session.getMessages()) {
            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            conversation.append("[").append(role).append("]: ").append(msg.getContent()).append("\n\n");
        }
        return conversation.toString();
    }

    private String buildSummarizePrompt(String conversationText) {
        return "请总结以下对话内容，生成一份问题记录。\n"
                + "要求：\n"
                + "1. 第一行输出一个简短标题（不超过30个字，不要包含任何标点或特殊字符，用于文件名）\n"
                + "2. 空一行后输出完整的 Markdown 格式总结，包含：\n"
                + "   - ## 问题描述\n"
                + "   - ## 原因分析\n"
                + "   - ## 解决方案\n"
                + "   - ## 关键变更\n"
                + "3. 只输出以上内容，不要输出其他任何内容\n\n"
                + "---\n以下是对话内容：\n\n"
                + conversationText;
    }
}
