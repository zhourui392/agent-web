package com.example.agentweb.infra.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实 codex --json 输出 fixture 端到端验证 {@link CodexEventNormalizer}。
 * <p>Fixture 抓取自 codex-cli 0.122.0 (Windows + packyapi 代理)。每份 NDJSON 对应一种核心
 * 场景, 测试逐行 normalize 后断言整体输出序列与前端契约一致。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
class CodexEventNormalizerFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CodexEventNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new CodexEventNormalizer();
    }

    @Test
    void helloWorld_shouldEmitInitTextResult() throws Exception {
        List<JsonNode> out = normalizeFixture("codex-fixtures/hello-world.jsonl");

        // 期望: system.init / agent_message text_delta / result.success — 共 3 条 (turn.started 被丢弃)
        assertEquals(3, out.size(), "hello-world 4 行 → 归一化 3 条");

        assertSystemInit(out.get(0), "019e2469-5cae-7b11-9a33-e4c2c1bde56f");
        assertTextDelta(out.get(1), "1+1=2。");
        assertResultSuccess(out.get(2), 17770, 2688, 10);
    }

    @Test
    void toolCall_shouldEmitInitTextToolUseToolResultTextResult() throws Exception {
        List<JsonNode> out = normalizeFixture("codex-fixtures/tool-call.jsonl");

        // 期望序列: system.init / text_delta1 / tool_use_start / input_json_delta /
        //          tool_result(success) / text_delta2 / result.success — 共 7 条
        assertEquals(7, out.size(), "tool-call 7 行原始 → 7 条 (turn.started 丢, command_execution.started 展开 2 条)");

        assertSystemInit(out.get(0), "019e246a-3b7e-79c0-9f6c-c4c4f3803ea5");
        assertTextDelta(out.get(1), "我会先读取项目说明，再用 PowerShell 列出当前目录文件及大小。");
        assertToolUseStart(out.get(2), "item_1", "shell");
        assertInputJsonDelta(out.get(3), "powershell.exe");
        assertToolResult(out.get(4), "item_1", false);
        // 后置 agent_message
        assertEquals("stream_event", out.get(5).get("type").asText());
        assertTrue(out.get(5).get("event").get("delta").get("text").asText().contains("alpha.txt"));
        assertResultSuccess(out.get(6), 35775, 19712, 203);
    }

    @Test
    void resume_shouldEmitInitTextResult() throws Exception {
        List<JsonNode> out = normalizeFixture("codex-fixtures/resume.jsonl");

        // resume 与新会话事件结构一致, 都发 thread.started
        assertEquals(3, out.size());
        assertSystemInit(out.get(0), "019e2469-5cae-7b11-9a33-e4c2c1bde56f");
        assertTextDelta(out.get(1), "2+2=4。");
        assertResultSuccess(out.get(2), 35628, 20224, 20);
    }

    @Test
    void longOutput_shouldStillProduceOnlyThreeEvents() throws Exception {
        List<JsonNode> out = normalizeFixture("codex-fixtures/long-output.jsonl");

        // 7.6KB 长输出也只有 4 行原始事件 → 3 条前端事件 (0.122 完全非流式)
        assertEquals(3, out.size(), "长输出仍然非流式: 4 行 → 3 条");
        assertSystemInit(out.get(0), null);
        // 长文本本体不断言具体内容, 只验证非空
        assertEquals("stream_event", out.get(1).get("type").asText());
        assertTrue(out.get(1).get("event").get("delta").get("text").asText().length() > 1000);
        assertEquals("result", out.get(2).get("type").asText());
        assertEquals("success", out.get(2).get("subtype").asText());
    }

    @Test
    void turnFailedBadModel_shouldDropReconnectsAndEmitResultError() throws Exception {
        List<JsonNode> out = normalizeFixture("codex-fixtures/turn-failed-badmodel.jsonl");

        // 9 行原始: thread.started + turn.started + 6 个 error (重连) + turn.failed
        // 期望归一化: system.init + result.error — 共 2 条
        assertEquals(2, out.size(), "重连噪音 6 条 error 全部丢弃, 只保留 init + result.error");
        assertSystemInit(out.get(0), null);
        assertEquals("result", out.get(1).get("type").asText());
        assertEquals("error", out.get(1).get("subtype").asText());
        assertTrue(out.get(1).get("error").get("message").asText().contains("503"));
        // result 字段供前端 json.result 守卫读取, 否则失败原因不显示
        assertTrue(out.get(1).get("result").asText().contains("503"), "result 字段须含失败原因");
    }

    @Test
    void toolCallFail_shouldMarkToolResultIsError() throws Exception {
        List<JsonNode> out = normalizeFixture("codex-fixtures/tool-call-fail.jsonl");

        // 工具调用 exit_code != 0 但 turn 成功: tool_result.is_error=true, 末尾仍是 result.success
        assertEquals(7, out.size());

        assertToolResult(out.get(4), "item_1", true);
        assertEquals("success", out.get(6).get("subtype").asText(), "工具失败但 turn 仍 completed");
    }

    // ── 辅助 ──

    private List<JsonNode> normalizeFixture(String classpath) throws IOException {
        List<JsonNode> out = new ArrayList<JsonNode>();
        InputStream in = getClass().getClassLoader().getResourceAsStream(classpath);
        assertNotNull(in, "fixture not found on classpath: " + classpath);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                for (String normalizedLine : normalizer.normalize(line)) {
                    out.add(MAPPER.readTree(normalizedLine));
                }
            }
        }
        return out;
    }

    private void assertSystemInit(JsonNode node, String expectedThreadId) {
        assertEquals("system", node.get("type").asText());
        assertEquals("init", node.get("subtype").asText());
        if (expectedThreadId != null) {
            assertEquals(expectedThreadId, node.get("session_id").asText());
        } else {
            assertTrue(node.get("session_id").asText().length() > 0);
        }
    }

    private void assertTextDelta(JsonNode node, String expectedText) {
        assertEquals("stream_event", node.get("type").asText());
        JsonNode evt = node.get("event");
        assertEquals("content_block_delta", evt.get("type").asText());
        assertEquals("text_delta", evt.get("delta").get("type").asText());
        assertEquals(expectedText, evt.get("delta").get("text").asText());
    }

    private void assertToolUseStart(JsonNode node, String expectedItemId, String expectedToolName) {
        assertEquals("stream_event", node.get("type").asText());
        JsonNode evt = node.get("event");
        assertEquals("content_block_start", evt.get("type").asText());
        JsonNode block = evt.get("content_block");
        assertEquals("tool_use", block.get("type").asText());
        assertEquals(expectedItemId, block.get("id").asText());
        assertEquals(expectedToolName, block.get("name").asText());
    }

    private void assertInputJsonDelta(JsonNode node, String expectedCommandSubstring) throws Exception {
        JsonNode evt = node.get("event");
        assertEquals("content_block_delta", evt.get("type").asText());
        assertEquals("input_json_delta", evt.get("delta").get("type").asText());
        String partialJson = evt.get("delta").get("partial_json").asText();
        JsonNode partial = MAPPER.readTree(partialJson);
        assertTrue(partial.get("command").asText().contains(expectedCommandSubstring));
    }

    private void assertToolResult(JsonNode node, String expectedToolUseId, boolean expectedIsError) {
        assertEquals("user", node.get("type").asText());
        JsonNode block = node.get("message").get("content").get(0);
        assertEquals("tool_result", block.get("type").asText());
        assertEquals(expectedToolUseId, block.get("tool_use_id").asText());
        assertEquals(expectedIsError, block.get("is_error").asBoolean());
    }

    private void assertResultSuccess(JsonNode node, int inputTokens, int cachedInputTokens, int outputTokens) {
        assertEquals("result", node.get("type").asText());
        assertEquals("success", node.get("subtype").asText());
        JsonNode usage = node.get("usage");
        assertEquals(inputTokens, usage.get("input_tokens").asInt());
        assertEquals(cachedInputTokens, usage.get("cached_input_tokens").asInt());
        assertEquals(outputTokens, usage.get("output_tokens").asInt());
    }
}
