package com.example.agentweb.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * CLI Agent 流式输出的纯文本抽取器。
 *
 * <p>支持三种主流形态:</p>
 * <ul>
 *   <li>Claude Code stream-json: 按行抽 {@code type=assistant} 中的 text 块, 终局
 *       {@code type=result} 行覆盖累积内容(权威结论)</li>
 *   <li>Anthropic SDK 增量流: 累积顶层 {@code content_block_delta.delta.text}</li>
 *   <li>Codex {@code exec --json} 经 {@code CodexEventNormalizer} 归一化后的形态: agent 正文
 *       包在 {@code type=stream_event} 的 {@code event.content_block_delta.delta.text_delta} 里,
 *       一个 turn 内含多条(工具调用前的过程叙述 + 终局结论), 只保留最后一条作结论(与
 *       Claude "最后一轮 assistant 即 result" 对等); 跳过 {@code input_json_delta}(工具入参);
 *       成功终局 {@code type=result} 行不带 {@code result} 字段, 不触发覆盖</li>
 * </ul>
 *
 * <p>注意 Claude stream-json 同时含 {@code type=assistant}(完整消息)与 {@code type=stream_event}
 * (冗余增量片段), 二者文本重复; 只有不含 {@code type=assistant} 行的 Codex 归一化输出才把
 * {@code stream_event} 当正文来源, 避免 Claude 侧把同一段文字累积两遍。</p>
 *
 * <p>剥离 tool_use / tool_result / thinking / system init 等噪音, 让下游(飞书结论回写、
 * issue-log 摘要)看到的就是 agent 真正的"有理有据"结论。非 JSON 行原样保留, 兼容历史纯文本。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-12
 */
@Component
public class StreamOutputExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String LF = "\n";
    private static final String TYPE_CONTENT_BLOCK_DELTA = "content_block_delta";
    private static final String TYPE_ASSISTANT = "assistant";
    private static final String TYPE_RESULT = "result";
    private static final String TYPE_TEXT = "text";
    private static final String TYPE_STREAM_EVENT = "stream_event";
    private static final String TYPE_TEXT_DELTA = "text_delta";

    public String extractPlainText(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return "";
        }
        String[] lines = rawOutput.split(LF);
        // 含 type=assistant 行即 Claude stream-json: stream_event 是其冗余增量片段,需跳过;
        // 不含则为 Codex 归一化输出,stream_event 才是正文唯一来源。
        boolean useStreamEvent = !containsAssistantLine(lines);
        StringBuilder plainText = new StringBuilder();
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("{")) {
                parseJsonLine(line, plainText, useStreamEvent);
            } else {
                plainText.append(line).append(LF);
            }
        }
        return plainText.toString().trim();
    }

    private boolean containsAssistantLine(String[] lines) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                continue;
            }
            try {
                if (TYPE_ASSISTANT.equals(OBJECT_MAPPER.readTree(trimmed).path("type").asText(""))) {
                    return true;
                }
            } catch (Exception ignored) {
                // 非合法 JSON 行,跳过
            }
        }
        return false;
    }

    private void parseJsonLine(String line, StringBuilder plainText, boolean useStreamEvent) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(line);
            String type = node.path("type").asText("");

            if (TYPE_CONTENT_BLOCK_DELTA.equals(type)) {
                appendDelta(node, plainText);
                return;
            }
            if (TYPE_STREAM_EVENT.equals(type)) {
                if (useStreamEvent) {
                    keepLatestStreamEvent(node, plainText);
                }
                return;
            }
            if (TYPE_ASSISTANT.equals(type)) {
                appendAssistantText(node, plainText);
                return;
            }
            if (TYPE_RESULT.equals(type)) {
                overrideWithResultIfPresent(node, plainText);
            }
        } catch (Exception ignored) {
            // 非合法 JSON 行,跳过即可,不影响后续行处理
        }
    }

    private void appendDelta(JsonNode node, StringBuilder plainText) {
        JsonNode delta = node.get("delta");
        if (delta != null && delta.has(TYPE_TEXT)) {
            plainText.append(delta.get(TYPE_TEXT).asText());
        }
    }

    /**
     * Codex 归一化事件: {@code {"type":"stream_event","event":{"type":"content_block_delta",
     * "delta":{"type":"text_delta","text":...}}}}。每条 stream_event 的 text_delta 是一条完整
     * agent 消息;一个 turn 内会有多条(工具调用前的过程叙述 + 终局结论),用最新一条覆盖
     * 累积文本,只留最后一条作结论 —— Codex 成功终局 {@code result} 行不带 {@code result}
     * 字段、无法触发覆盖,故在此对齐 Claude "最后一轮 assistant 即结论" 的语义。
     * {@code input_json_delta}(工具入参)与 {@code content_block_start}(tool_use)一并跳过。
     */
    private void keepLatestStreamEvent(JsonNode node, StringBuilder plainText) {
        JsonNode event = node.path("event");
        if (!TYPE_CONTENT_BLOCK_DELTA.equals(event.path("type").asText())) {
            return;
        }
        JsonNode delta = event.path("delta");
        if (!TYPE_TEXT_DELTA.equals(delta.path("type").asText())) {
            return;
        }
        String text = delta.path(TYPE_TEXT).asText("");
        if (text.isEmpty()) {
            return;
        }
        plainText.setLength(0);
        plainText.append(text);
    }

    private void appendAssistantText(JsonNode node, StringBuilder plainText) {
        JsonNode content = node.path("message").path("content");
        if (!content.isArray()) {
            return;
        }
        for (JsonNode block : content) {
            if (TYPE_TEXT.equals(block.path("type").asText())) {
                plainText.append(block.path("text").asText());
            }
        }
    }

    private void overrideWithResultIfPresent(JsonNode node, StringBuilder plainText) {
        String resultText = node.path("result").asText("");
        // 仅当终局结果非空时,用其覆盖累积文本(终局结果更权威)
        if (!resultText.isEmpty()) {
            plainText.setLength(0);
            plainText.append(resultText);
        }
    }
}
