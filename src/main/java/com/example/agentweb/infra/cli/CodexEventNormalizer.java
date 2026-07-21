package com.example.agentweb.infra.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Codex CLI {@code exec --json} 事件归一化器。
 * <p>把 codex 原始 NDJSON 事件映射成前端约定的 Claude-兼容事件契约
 * (参见 static/js/app.js 的 SSE chunk listener 与 parseStreamJson)。
 *
 * <p>核心策略 (基于 src/test/resources/codex-fixtures/ 实测):
 * <ul>
 *   <li>无状态: 每条 codex 事件独立映射到 0..N 条前端事件 (取决于事件类型)</li>
 *   <li>{@code agent_message} 立即推 (用户拍板): 走 {@code stream_event.text_delta} 而非
 *       {@code assistant} 类型, 让前端 segment 状态机自然分段</li>
 *   <li>{@code command_execution} 启动展开成 2 条: {@code content_block_start.tool_use} +
 *       {@code content_block_delta.input_json_delta}, 工具失败时 {@code is_error=true}</li>
 *   <li>{@code error} (重连噪音) 静默丢弃; {@code turn.failed} 才发 {@code result.error}</li>
 *   <li>未识别事件类型 / item.type 静默丢弃, v1 不透传</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
public class CodexEventNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String EVENT_THREAD_STARTED = "thread.started";
    private static final String EVENT_TURN_COMPLETED = "turn.completed";
    private static final String EVENT_TURN_FAILED = "turn.failed";
    private static final String EVENT_ITEM_STARTED = "item.started";
    private static final String EVENT_ITEM_COMPLETED = "item.completed";

    private static final String ITEM_AGENT_MESSAGE = "agent_message";
    private static final String ITEM_COMMAND_EXECUTION = "command_execution";

    private static final String STATUS_FAILED = "failed";

    private static final String FALLBACK_FAILURE_MESSAGE = "Codex 任务失败";

    public List<String> normalize(String codexLine) {
        if (codexLine == null || codexLine.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JsonNode node = MAPPER.readTree(codexLine);
            String type = readText(node, "type");
            return dispatch(type, node);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<String> dispatch(String type, JsonNode node) {
        if (type == null) {
            return Collections.emptyList();
        }
        switch (type) {
            case EVENT_THREAD_STARTED:
                return mapThreadStarted(node);
            case EVENT_TURN_COMPLETED:
                return mapTurnCompleted(node);
            case EVENT_TURN_FAILED:
                return mapTurnFailed(node);
            case EVENT_ITEM_STARTED:
                return mapItemStarted(node);
            case EVENT_ITEM_COMPLETED:
                return mapItemCompleted(node);
            default:
                return Collections.emptyList();
        }
    }

    private List<String> mapThreadStarted(JsonNode node) {
        String threadId = readText(node, "thread_id");
        if (threadId == null || threadId.isEmpty()) {
            return Collections.emptyList();
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "system");
        out.put("subtype", "init");
        out.put("session_id", threadId);
        return Collections.singletonList(out.toString());
    }

    private List<String> mapTurnCompleted(JsonNode node) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "result");
        out.put("subtype", "success");
        JsonNode usage = node.get("usage");
        if (usage != null) {
            out.set("usage", usage);
        }
        return Collections.singletonList(out.toString());
    }

    private List<String> mapTurnFailed(JsonNode node) {
        JsonNode err = node.get("error");
        String message = (err != null && err.has("message"))
                ? err.get("message").asText() : FALLBACK_FAILURE_MESSAGE;

        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "result");
        out.put("subtype", "error");
        ObjectNode errOut = MAPPER.createObjectNode();
        errOut.put("message", message);
        out.set("error", errOut);
        // result 字段: 前端 parseStreamJson / SSE chunk listener 用 json.result 守卫读失败原因,
        // 缺失则失败提示被吞 —— 必须与 error.message 同步输出
        out.put("result", message);
        return Collections.singletonList(out.toString());
    }

    private List<String> mapItemStarted(JsonNode node) {
        JsonNode item = node.get("item");
        if (item == null) {
            return Collections.emptyList();
        }
        String itemType = readText(item, "type");
        if (ITEM_COMMAND_EXECUTION.equals(itemType)) {
            return mapCommandExecutionStarted(item);
        }
        // agent_message 的 item.started 实测未出现; 即便出现也等 item.completed 一次性发文本
        return Collections.emptyList();
    }

    private List<String> mapItemCompleted(JsonNode node) {
        JsonNode item = node.get("item");
        if (item == null) {
            return Collections.emptyList();
        }
        String itemType = readText(item, "type");
        if (ITEM_AGENT_MESSAGE.equals(itemType)) {
            return mapAgentMessageCompleted(item);
        }
        if (ITEM_COMMAND_EXECUTION.equals(itemType)) {
            return mapCommandExecutionCompleted(item);
        }
        return Collections.emptyList();
    }

    private List<String> mapAgentMessageCompleted(JsonNode item) {
        String text = readText(item, "text");
        if (text == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(textDelta(text));
    }

    private List<String> mapCommandExecutionStarted(JsonNode item) {
        String itemId = readText(item, "id");
        String command = readText(item, "command");

        List<String> out = new ArrayList<String>(2);
        out.add(toolUseStart(itemId, command));
        out.add(toolInputDelta(command));
        return out;
    }

    private List<String> mapCommandExecutionCompleted(JsonNode item) {
        String itemId = readText(item, "id");
        String aggregated = readText(item, "aggregated_output");
        boolean isError = STATUS_FAILED.equals(readText(item, "status"));

        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "user");
        ObjectNode message = MAPPER.createObjectNode();
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", itemId == null ? "" : itemId);
        block.put("content", aggregated == null ? "" : aggregated);
        block.put("is_error", isError);
        message.set("content", MAPPER.createArrayNode().add(block));
        out.set("message", message);
        return Collections.singletonList(out.toString());
    }

    private String textDelta(String text) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "stream_event");
        ObjectNode evt = MAPPER.createObjectNode();
        evt.put("type", "content_block_delta");
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("type", "text_delta");
        delta.put("text", text);
        evt.set("delta", delta);
        out.set("event", evt);
        return out.toString();
    }

    private String toolUseStart(String itemId, String command) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "stream_event");
        ObjectNode evt = MAPPER.createObjectNode();
        evt.put("type", "content_block_start");
        ObjectNode block = MAPPER.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", itemId == null ? "" : itemId);
        block.put("name", "shell");
        block.set("input", MAPPER.createObjectNode());
        evt.set("content_block", block);
        out.set("event", evt);
        return out.toString();
    }

    private String toolInputDelta(String command) {
        ObjectNode partial = MAPPER.createObjectNode();
        partial.put("command", command == null ? "" : command);

        ObjectNode out = MAPPER.createObjectNode();
        out.put("type", "stream_event");
        ObjectNode evt = MAPPER.createObjectNode();
        evt.put("type", "content_block_delta");
        ObjectNode delta = MAPPER.createObjectNode();
        delta.put("type", "input_json_delta");
        delta.put("partial_json", partial.toString());
        evt.set("delta", delta);
        out.set("event", evt);
        return out.toString();
    }

    private String readText(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
