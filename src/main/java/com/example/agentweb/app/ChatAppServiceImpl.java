package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.AgentType;
import com.example.agentweb.domain.ChatMessage;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.SessionRepository;
import com.example.agentweb.domain.SlashCommand;
import com.example.agentweb.domain.SlashCommandExpander;
import com.example.agentweb.infra.InMemorySessionRepo;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
public class ChatAppServiceImpl implements ChatAppService {

    private final InMemorySessionRepo repo;
    private final SessionRepository sessionRepository;
    private final AgentGateway gateway;
    private final Executor agentExecutor;
    private final SlashCommandExpander commandExpander;

    public ChatAppServiceImpl(InMemorySessionRepo repo,
                              SessionRepository sessionRepository,
                              AgentGateway gateway,
                              Executor agentExecutor,
                              SlashCommandExpander commandExpander) {
        this.repo = repo;
        this.sessionRepository = sessionRepository;
        this.gateway = gateway;
        this.agentExecutor = agentExecutor;
        this.commandExpander = commandExpander;
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
        ChatSession s = repo.find(sessionId);
        if (s == null) {
            // Fallback to persistent storage (e.g. after server restart or resuming from history)
            s = sessionRepository.findById(sessionId);
            if (s != null) {
                repo.save(s);
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
        final StringBuilder fullResponse = new StringBuilder();
        final boolean[] resumeIdSaved = {false};

        final String envFinal = env;
        agentExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String cliMessage = commandExpander.expandIfCommand(s.getWorkingDir(), message);
                    gateway.runStream(s.getAgentType(), s.getWorkingDir(), cliMessage, sessionId, resumeId, envFinal,
                            new java.util.function.Consumer<String>() {
                                @Override
                                public void accept(String chunk) {
                                    fullResponse.append(chunk).append("\n");
                                    // Extract and persist resumeId from first chunk containing session_id
                                    if (!resumeIdSaved[0]) {
                                        try {
                                            if (chunk.contains("\"session_id\"")) {
                                                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                                                com.fasterxml.jackson.databind.JsonNode node = om.readTree(chunk);
                                                if (node.has("session_id")) {
                                                    String cliSessionId = node.get("session_id").asText();
                                                    if (cliSessionId != null && !cliSessionId.isEmpty()) {
                                                        sessionRepository.updateResumeId(sessionId, cliSessionId);
                                                        resumeIdSaved[0] = true;
                                                    }
                                                }
                                            }
                                        } catch (Exception ignored) {
                                            // not JSON or no session_id field
                                        }
                                    }
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
                                    // persist complete assistant response
                                    String response = fullResponse.toString().trim();
                                    if (!response.isEmpty()) {
                                        ChatMessage assistantMsg = new ChatMessage("assistant", response);
                                        sessionRepository.addMessage(sessionId, assistantMsg);
                                    }
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

    @Override
    public void stopSession(String sessionId) {
        gateway.stopStream(sessionId);
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

        // Build conversation text for summarization prompt
        StringBuilder conversation = new StringBuilder();
        for (ChatMessage msg : s.getMessages()) {
            String role = "user".equals(msg.getRole()) ? "用户" : "助手";
            conversation.append("[").append(role).append("]: ").append(msg.getContent()).append("\n\n");
        }

        // Determine next issue number
        Path issuesDir = Paths.get(s.getWorkingDir(), "docs", "issue-log", "issues");
        Files.createDirectories(issuesDir);
        int nextNum = 1;
        File[] existing = issuesDir.toFile().listFiles((dir, name) -> name.matches("I-\\d{3}-.*\\.md"));
        if (existing != null) {
            for (File f : existing) {
                try {
                    int num = Integer.parseInt(f.getName().substring(2, 5));
                    if (num >= nextNum) nextNum = num + 1;
                } catch (NumberFormatException ignored) {}
            }
        }
        String issueId = String.format("I-%03d", nextNum);

        // Call CLI to generate summary
        String prompt = "请总结以下对话内容，生成一份问题记录。\n"
                + "要求：\n"
                + "1. 第一行输出一个简短标题（不超过30个字，不要包含任何标点或特殊字符，用于文件名）\n"
                + "2. 空一行后输出完整的 Markdown 格式总结，包含：\n"
                + "   - ## 问题描述\n"
                + "   - ## 原因分析\n"
                + "   - ## 解决方案\n"
                + "   - ## 关键变更\n"
                + "3. 只输出以上内容，不要输出其他任何内容\n\n"
                + "---\n以下是对话内容：\n\n"
                + conversation.toString();

        String rawOutput = gateway.runOnce(AgentType.CLAUDE, s.getWorkingDir(), prompt);

        // Parse CLI output: extract text content from stream-json
        StringBuilder plainText = new StringBuilder();
        for (String line : rawOutput.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("{")) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode node = om.readTree(line);
                    // stream-json content_block_delta with text
                    if (node.has("type") && "content_block_delta".equals(node.get("type").asText())) {
                        com.fasterxml.jackson.databind.JsonNode delta = node.get("delta");
                        if (delta != null && delta.has("text")) {
                            plainText.append(delta.get("text").asText());
                        }
                    }
                    // result type
                    if (node.has("type") && "result".equals(node.get("type").asText())) {
                        if (node.has("result")) {
                            plainText.setLength(0);
                            plainText.append(node.get("result").asText());
                        }
                    }
                } catch (Exception ignored) {}
            } else {
                plainText.append(line).append("\n");
            }
        }

        String summaryText = plainText.toString().trim();
        // First line is the title, rest is the markdown body
        String title;
        String body;
        int firstNewline = summaryText.indexOf('\n');
        if (firstNewline > 0) {
            title = summaryText.substring(0, firstNewline).trim();
            body = summaryText.substring(firstNewline).trim();
        } else {
            title = summaryText;
            body = summaryText;
        }

        // Sanitize title for filename
        String safeTitle = title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_-]", "");
        if (safeTitle.length() > 40) safeTitle = safeTitle.substring(0, 40);
        if (safeTitle.isEmpty()) safeTitle = "summary";
        String fileName = issueId + "-" + safeTitle + ".md";

        // Write issue file
        Path issueFile = issuesDir.resolve(fileName);
        String issueContent = "# " + issueId + " " + title + "\n\n"
                + "- **会话ID**: " + sessionId + "\n"
                + "- **工作目录**: " + s.getWorkingDir() + "\n"
                + "- **创建时间**: " + java.time.LocalDate.now() + "\n\n"
                + body + "\n";
        Files.write(issueFile, issueContent.getBytes(StandardCharsets.UTF_8));

        // Update index.md
        Path indexFile = Paths.get(s.getWorkingDir(), "docs", "issue-log", "index.md");
        StringBuilder indexContent = new StringBuilder();
        if (Files.exists(indexFile)) {
            indexContent.append(new String(Files.readAllBytes(indexFile), StandardCharsets.UTF_8));
        } else {
            indexContent.append("# Issue Log\n\n")
                    .append("| ID | 标题 | 日期 |\n")
                    .append("|------|------|------|\n");
        }
        indexContent.append("| [").append(issueId).append("](issues/").append(fileName).append(") | ")
                .append(title).append(" | ").append(java.time.LocalDate.now()).append(" |\n");
        Files.write(indexFile, indexContent.toString().getBytes(StandardCharsets.UTF_8));

        Map<String, Object> result = new HashMap<>();
        result.put("issueId", issueId);
        result.put("fileName", fileName);
        result.put("title", title);
        result.put("filePath", issueFile.toString());
        return result;
    }
}
