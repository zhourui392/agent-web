package com.example.agentweb.app;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.SessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * 处理 CLI Agent 流式输出的通用回调构造器。
 * <p>负责：
 * <ul>
 *   <li>从首个含 session id 的 chunk 中抽取 resumeId（委托给 {@link AgentGateway#extractResumeId}，
 *       由具体方言决定字段语义：Claude 解 {@code system.init.session_id}，Codex 解
 *       {@code thread.started.thread_id}）并持久化。</li>
 *   <li>在流结束时将累积响应保存为 assistant 消息。</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
@Slf4j
public class StreamChunkHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TOOL_RESULT_CONTENT_CHARS = 12_000;
    private static final String TRUNCATED_TOOL_RESULT_SUFFIX_PREFIX =
            "\n... [agent-web truncated tool output: original ";

    private final SessionRepository sessionRepository;
    private final String sessionId;
    private final AgentGateway gateway;
    private final AgentType agentType;
    private final StringBuilder fullResponse = new StringBuilder();
    private volatile boolean resumeIdSaved = false;
    private volatile String recallJson;
    private volatile LongConsumer assistantPersistedCallback;
    private int chunkCount;

    public StreamChunkHandler(SessionRepository sessionRepository,
                              String sessionId,
                              AgentGateway gateway,
                              AgentType agentType) {
        this.sessionRepository = sessionRepository;
        this.sessionId = sessionId;
        this.gateway = gateway;
        this.agentType = agentType;
    }

    /**
     * Sets the public recall payload for this turn. When non-null, the assistant
     * message persisted on exit also writes {@code chat_message_recall} so history
     * can replay the recall card.
     */
    public void setRecallJson(String recallJson) {
        this.recallJson = recallJson;
    }

    /**
     * Registers a lightweight callback after assistant message persistence. The
     * app layer uses it to backfill recall attempt {@code assistant_message_id}.
     */
    public void onAssistantPersisted(LongConsumer callback) {
        this.assistantPersistedCallback = callback;
    }

    /**
     * Creates the callback that handles each CLI chunk.
     * <p>Extracts and persists the dialect-specific resumeId, then accumulates the
     * normalized response text.</p>
     *
     * @param additionalAction extra chunk action, such as sending SSE; may be null
     * @return chunk consumer
     */
    public Consumer<String> onChunk(final Consumer<String> additionalAction) {
        return new Consumer<String>() {
            @Override
            public void accept(String rawChunk) {
                // resumeId 抽取必须用原始事件 (方言按自己的字段语义解析)
                extractResumeIdIfNeeded(rawChunk);
                // 累积 + 推给前端的都用归一化后的统一事件契约:
                // 落库归一化事件, 前端 parseStreamJson 才能解析 codex 历史会话 (Claude 路径归一化为直通);
                // 单条 codex 事件可展开为 0..N 条前端事件 (重连噪音返回空 / 工具调用启动展开两条)
                List<String> normalized = gateway.normalizeChunk(agentType, rawChunk);
                if (normalized == null) {
                    return;
                }
                for (String line : normalized) {
                    String safeLine = truncateToolResultLine(line);
                    fullResponse.append(safeLine).append("\n");
                    chunkCount++;
                    if (log.isDebugEnabled()) {
                        log.debug("chunk-handled sessionId={} agentType={} chunkIndex={} chunkLen={}",
                                sessionId, agentType, chunkCount, safeLine.length());
                    }
                    if (additionalAction != null) {
                        additionalAction.accept(safeLine);
                    }
                }
            }
        };
    }

    /**
     * 创建流结束时的回调，将累积的完整响应保存为 assistant 消息。
     *
     * @param additionalAction 额外的结束处理逻辑（如发送 SSE exit 事件），可为 null
     * @return exit 消费者
     */
    public IntConsumer onExit(final IntConsumer additionalAction) {
        return new IntConsumer() {
            @Override
            public void accept(int code) {
                String response = fullResponse.toString().trim();
                if (!response.isEmpty()) {
                    ChatMessage assistant = new ChatMessage("assistant", response);
                    LongConsumer callback = assistantPersistedCallback;
                    if (recallJson != null || callback != null) {
                        long msgId = sessionRepository.addMessageReturningId(sessionId, assistant);
                        if (recallJson != null) {
                            sessionRepository.saveRecall(msgId, recallJson);
                            log.info("chat-assistant-persisted-with-recall sessionId={} msgId={} responseLen={} exitCode={}",
                                    sessionId, msgId, response.length(), code);
                        }
                        if (callback != null) {
                            try {
                                callback.accept(msgId);
                            } catch (RuntimeException e) {
                                log.warn("chat-assistant-persisted-callback-failed sessionId={} msgId={} reason={}",
                                        sessionId, msgId, e.getMessage());
                            }
                        }
                    } else {
                        sessionRepository.addMessage(sessionId, assistant);
                    }
                    log.info("chat-assistant-persisted sessionId={} agentType={} chunkCount={} responseLen={} exitCode={}",
                            sessionId, agentType, chunkCount, response.length(), code);
                } else {
                    log.warn("chat-assistant-empty sessionId={} agentType={} chunkCount={} exitCode={}",
                            sessionId, agentType, chunkCount, code);
                }
                if (additionalAction != null) {
                    additionalAction.accept(code);
                }
            }
        };
    }

    /**
     * Returns the normalized accumulation without persisting it. ChatRun completion owns
     * the atomic assistant-message plus terminal-state transaction.
     */
    public String accumulatedResponse() {
        return fullResponse.toString().trim();
    }

    private void extractResumeIdIfNeeded(String chunk) {
        if (resumeIdSaved) {
            return;
        }
        String cliSessionId = gateway.extractResumeId(agentType, chunk);
        if (cliSessionId == null || cliSessionId.isEmpty()) {
            return;
        }
        sessionRepository.updateResumeId(sessionId, cliSessionId);
        resumeIdSaved = true;
        log.info("chat-resume-id-captured sessionId={} agentType={} resumeId={}",
                sessionId, agentType, cliSessionId);
    }

    private String truncateToolResultLine(String line) {
        if (line == null || !line.contains("\"tool_result\"") || !line.contains("\"content\"")) {
            return line;
        }
        try {
            JsonNode json = MAPPER.readTree(line);
            JsonNode content = json.path("message").path("content");
            if (!content.isArray()) {
                return line;
            }
            boolean changed = false;
            for (JsonNode block : content) {
                if (block instanceof ObjectNode
                        && "tool_result".equals(block.path("type").asText())
                        && block.path("content").isTextual()) {
                    String raw = block.path("content").asText();
                    String truncated = truncateToolResult(raw);
                    if (!raw.equals(truncated)) {
                        ((ObjectNode) block).put("content", truncated);
                        changed = true;
                    }
                }
            }
            return changed ? json.toString() : line;
        } catch (Exception ignored) {
            return line;
        }
    }

    private String truncateToolResult(String content) {
        if (content == null || content.length() <= MAX_TOOL_RESULT_CONTENT_CHARS) {
            return content == null ? "" : content;
        }
        return content.substring(0, MAX_TOOL_RESULT_CONTENT_CHARS)
                + TRUNCATED_TOOL_RESULT_SUFFIX_PREFIX
                + content.length() + " chars, showing first "
                + MAX_TOOL_RESULT_CONTENT_CHARS + " chars]";
    }
}
