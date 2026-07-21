package com.example.agentweb.infra.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codex 事件归一化器单元测试。
 * <p>所有期望值来自实测 fixture (src/test/resources/codex-fixtures/) + 前端契约
 * (static/js/app.js#parseStreamJson / SSE chunk listener)。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
class CodexEventNormalizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CodexEventNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new CodexEventNormalizer();
    }

    // ── 第 1 轮：单事件 1:1 / 1:0 简单映射 ──

    @Test
    void normalize_threadStarted_shouldEmitSystemInitWithSessionId() throws Exception {
        String line = "{\"type\":\"thread.started\",\"thread_id\":\"019e2469-5cae-7b11-9a33-e4c2c1bde56f\"}";

        List<String> out = normalizer.normalize(line);

        assertEquals(1, out.size());
        JsonNode json = MAPPER.readTree(out.get(0));
        assertEquals("system", json.get("type").asText());
        assertEquals("init", json.get("subtype").asText());
        assertEquals("019e2469-5cae-7b11-9a33-e4c2c1bde56f", json.get("session_id").asText());
    }

    @Test
    void normalize_turnStarted_shouldBeDropped() {
        String line = "{\"type\":\"turn.started\"}";

        List<String> out = normalizer.normalize(line);

        assertTrue(out.isEmpty(), "turn.started 不映射给前端");
    }

    @Test
    void normalize_turnCompleted_shouldEmitResultSuccessWithUsage() throws Exception {
        String line = "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"cached_input_tokens\":50,\"output_tokens\":20}}";

        List<String> out = normalizer.normalize(line);

        assertEquals(1, out.size());
        JsonNode json = MAPPER.readTree(out.get(0));
        assertEquals("result", json.get("type").asText());
        assertEquals("success", json.get("subtype").asText());
        assertEquals(100, json.get("usage").get("input_tokens").asInt());
        assertEquals(50, json.get("usage").get("cached_input_tokens").asInt());
        assertEquals(20, json.get("usage").get("output_tokens").asInt());
    }

    @Test
    void normalize_invalidJson_shouldBeDropped() {
        String line = "this is not JSON";

        List<String> out = normalizer.normalize(line);

        assertTrue(out.isEmpty(), "非 JSON 行静默丢弃");
    }

    @Test
    void normalize_emptyOrNullLine_shouldBeDropped() {
        assertTrue(normalizer.normalize(null).isEmpty());
        assertTrue(normalizer.normalize("").isEmpty());
        assertTrue(normalizer.normalize("   ").isEmpty());
    }

    @Test
    void normalize_unknownEventType_shouldBeDropped() {
        String line = "{\"type\":\"some.future.event\",\"foo\":\"bar\"}";

        List<String> out = normalizer.normalize(line);

        assertTrue(out.isEmpty(), "未知事件类型静默丢弃 (v1 不做透传, 后续可加策略)");
    }

    // ── 第 2 轮：agent_message + error/重连 ──

    @Test
    void normalize_agentMessageCompleted_shouldEmitStreamEventTextDelta() throws Exception {
        String line = "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\",\"text\":\"1+1=2。\"}}";

        List<String> out = normalizer.normalize(line);

        assertEquals(1, out.size());
        JsonNode json = MAPPER.readTree(out.get(0));
        assertEquals("stream_event", json.get("type").asText());
        JsonNode evt = json.get("event");
        assertEquals("content_block_delta", evt.get("type").asText());
        assertEquals("text_delta", evt.get("delta").get("type").asText());
        assertEquals("1+1=2。", evt.get("delta").get("text").asText());
    }

    @Test
    void normalize_agentMessageWithNewlines_shouldPreserveTextVerbatim() throws Exception {
        String line = "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_2\",\"type\":\"agent_message\",\"text\":\"line1\\nline2\\n\\nline4\"}}";

        List<String> out = normalizer.normalize(line);

        assertEquals(1, out.size());
        JsonNode evt = MAPPER.readTree(out.get(0)).get("event");
        assertEquals("line1\nline2\n\nline4", evt.get("delta").get("text").asText());
    }

    @Test
    void normalize_agentMessageStarted_shouldBeDropped() {
        // 实测 fixture 未出现 item.started/agent_message,但保守起见:若出现则丢弃,等 item.completed 一次性给文本
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\",\"text\":\"\"}}";

        List<String> out = normalizer.normalize(line);

        assertTrue(out.isEmpty());
    }

    @Test
    void normalize_errorReconnecting_shouldBeDropped() {
        String line = "{\"type\":\"error\",\"message\":\"Reconnecting... 1/5 (unexpected status 503)\"}";

        List<String> out = normalizer.normalize(line);

        assertTrue(out.isEmpty(), "重连噪音不推给前端");
    }

    @Test
    void normalize_errorFinal_shouldBeDropped() {
        // 5 次重连后最后一条 error 与后续 turn.failed 同义,统一让 turn.failed 接管
        String line = "{\"type\":\"error\",\"message\":\"unexpected status 503 Service Unavailable\"}";

        List<String> out = normalizer.normalize(line);

        assertTrue(out.isEmpty());
    }

    @Test
    void normalize_turnFailed_shouldEmitResultErrorWithMessage() throws Exception {
        String line = "{\"type\":\"turn.failed\",\"error\":{\"message\":\"unexpected status 503: 模型不存在\"}}";

        List<String> out = normalizer.normalize(line);

        assertEquals(1, out.size());
        JsonNode json = MAPPER.readTree(out.get(0));
        assertEquals("result", json.get("type").asText());
        assertEquals("error", json.get("subtype").asText());
        assertEquals("unexpected status 503: 模型不存在", json.get("error").get("message").asText());
        // 前端 parseStreamJson / SSE chunk listener 按 json.result 读失败原因, 必须同时给 result 字段
        assertEquals("unexpected status 503: 模型不存在", json.get("result").asText());
    }

    @Test
    void normalize_turnFailedMissingMessage_shouldEmitResultWithFallback() throws Exception {
        // error.message 缺失时 result 仍须非空, 否则前端 json.result 守卫吞掉失败提示
        String line = "{\"type\":\"turn.failed\",\"error\":{}}";

        List<String> out = normalizer.normalize(line);

        assertEquals(1, out.size());
        JsonNode json = MAPPER.readTree(out.get(0));
        assertEquals("error", json.get("subtype").asText());
        assertTrue(json.get("result").asText().length() > 0, "result 必须非空供前端显示");
    }

    // ── 第 3 轮：command_execution（1:N 映射） ──

    @Test
    void normalize_commandExecutionStarted_shouldEmitContentBlockStartAndInputDelta() throws Exception {
        String line = "{\"type\":\"item.started\",\"item\":{\"id\":\"item_1\",\"type\":\"command_execution\",\"command\":\"ls -la\",\"aggregated_output\":\"\",\"exit_code\":null,\"status\":\"in_progress\"}}";

        List<String> out = normalizer.normalize(line);

        assertEquals(2, out.size(), "工具调用启动 → 1 条 content_block_start + 1 条 input_json_delta");

        JsonNode start = MAPPER.readTree(out.get(0));
        assertEquals("stream_event", start.get("type").asText());
        JsonNode startEvt = start.get("event");
        assertEquals("content_block_start", startEvt.get("type").asText());
        JsonNode block = startEvt.get("content_block");
        assertEquals("tool_use", block.get("type").asText());
        assertEquals("item_1", block.get("id").asText());
        assertEquals("shell", block.get("name").asText());

        JsonNode delta = MAPPER.readTree(out.get(1));
        JsonNode deltaEvt = delta.get("event");
        assertEquals("content_block_delta", deltaEvt.get("type").asText());
        assertEquals("input_json_delta", deltaEvt.get("delta").get("type").asText());
        // partial_json 是工具输入 JSON 字符串, 含 command 字段
        String partial = deltaEvt.get("delta").get("partial_json").asText();
        JsonNode partialJson = MAPPER.readTree(partial);
        assertEquals("ls -la", partialJson.get("command").asText());
    }

    @Test
    void normalize_commandExecutionCompletedOk_shouldEmitToolResultNotError() throws Exception {
        String line = "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_1\",\"type\":\"command_execution\",\"command\":\"ls\",\"aggregated_output\":\"file1\\nfile2\\n\",\"exit_code\":0,\"status\":\"completed\"}}";

        List<String> out = normalizer.normalize(line);

        assertEquals(1, out.size());
        JsonNode json = MAPPER.readTree(out.get(0));
        assertEquals("user", json.get("type").asText());
        JsonNode block = json.get("message").get("content").get(0);
        assertEquals("tool_result", block.get("type").asText());
        assertEquals("item_1", block.get("tool_use_id").asText());
        assertEquals("file1\nfile2\n", block.get("content").asText());
        assertEquals(false, block.get("is_error").asBoolean());
    }

    @Test
    void normalize_commandExecutionFailed_shouldMarkIsErrorTrue() throws Exception {
        String line = "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_1\",\"type\":\"command_execution\",\"command\":\"cat missing\",\"aggregated_output\":\"cat: not found\",\"exit_code\":1,\"status\":\"failed\"}}";

        List<String> out = normalizer.normalize(line);

        assertEquals(1, out.size());
        JsonNode block = MAPPER.readTree(out.get(0)).get("message").get("content").get(0);
        assertEquals(true, block.get("is_error").asBoolean());
        assertEquals("cat: not found", block.get("content").asText());
    }

    // ── 边界 / 鲁棒性 ──

    @Test
    void normalize_itemCompletedUnknownItemType_shouldBeDropped() {
        // reasoning / mcp_tool_call 等未实测类型, v1 直接丢弃
        String line = "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_x\",\"type\":\"reasoning\",\"text\":\"thinking...\"}}";

        List<String> out = normalizer.normalize(line);

        assertTrue(out.isEmpty(), "未识别的 item.type 静默丢弃");
    }

    @Test
    void normalize_threadStartedMissingThreadId_shouldBeDropped() {
        String line = "{\"type\":\"thread.started\"}";

        List<String> out = normalizer.normalize(line);

        assertTrue(out.isEmpty(), "thread_id 缺失则不发 init 事件");
    }

    @Test
    void normalize_turnCompletedMissingUsage_shouldStillEmitResult() throws Exception {
        // 防御: usage 缺失也至少发一条 result 让前端结束
        String line = "{\"type\":\"turn.completed\"}";

        List<String> out = normalizer.normalize(line);

        assertEquals(1, out.size());
        JsonNode json = MAPPER.readTree(out.get(0));
        assertEquals("result", json.get("type").asText());
        assertEquals("success", json.get("subtype").asText());
    }
}
