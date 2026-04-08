package com.example.agentweb.app;

import com.example.agentweb.domain.ChatMessage;
import com.example.agentweb.domain.SessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 处理 CLI Agent 流式输出的通用回调构造器。
 * <p>负责：从首个包含 session_id 的 chunk 中提取 resumeId 并持久化；
 * 在流结束时将完整响应保存为 assistant 消息。</p>
 */
class StreamChunkHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SessionRepository sessionRepository;
    private final String sessionId;
    private final StringBuilder fullResponse = new StringBuilder();
    private volatile boolean resumeIdSaved = false;

    StreamChunkHandler(SessionRepository sessionRepository, String sessionId) {
        this.sessionRepository = sessionRepository;
        this.sessionId = sessionId;
    }

    /**
     * 创建处理每个 chunk 的回调。
     * <p>自动从 JSON chunk 中提取 resumeId 并持久化，同时累积完整响应文本。</p>
     *
     * @param additionalAction 额外的 chunk 处理逻辑（如发送 SSE 事件），可为 null
     * @return chunk 消费者
     */
    Consumer<String> onChunk(final Consumer<String> additionalAction) {
        return new Consumer<String>() {
            @Override
            public void accept(String chunk) {
                fullResponse.append(chunk).append("\n");
                extractResumeIdIfNeeded(chunk);
                if (additionalAction != null) {
                    additionalAction.accept(chunk);
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
    IntConsumer onExit(final IntConsumer additionalAction) {
        return new IntConsumer() {
            @Override
            public void accept(int code) {
                String response = fullResponse.toString().trim();
                if (!response.isEmpty()) {
                    sessionRepository.addMessage(sessionId, new ChatMessage("assistant", response));
                }
                if (additionalAction != null) {
                    additionalAction.accept(code);
                }
            }
        };
    }

    private void extractResumeIdIfNeeded(String chunk) {
        if (resumeIdSaved) {
            return;
        }
        if (!chunk.contains("\"session_id\"")) {
            return;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(chunk);
            if (node.has("session_id")) {
                String cliSessionId = node.get("session_id").asText();
                if (cliSessionId != null && !cliSessionId.isEmpty()) {
                    sessionRepository.updateResumeId(sessionId, cliSessionId);
                    resumeIdSaved = true;
                }
            }
        } catch (Exception ignored) {
            // not JSON or no session_id field
        }
    }
}
