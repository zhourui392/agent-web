package com.example.agentweb.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@link StreamOutputExtractor#extractPlainText(String)} 对 CLI 流式输出的抽取行为。
 *
 * <p>抽取规则:</p>
 * <ul>
 *   <li>{@code type=assistant} 行: 累积 content 数组中的 text 块, 丢弃 tool_use / thinking</li>
 *   <li>{@code type=result} 行: 终局结果, 非空时覆盖前面累积内容(最权威)</li>
 *   <li>{@code type=content_block_delta} 行: 累积 delta.text</li>
 *   <li>{@code type=stream_event} 行(Codex 归一化): 只保留最后一条 event.content_block_delta
 *       的 text_delta 作最终结论, 跳过 input_json_delta 与中间过程叙述</li>
 *   <li>非 JSON 行: 原样保留(兼容历史纯文本)</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-12
 */
public class StreamOutputExtractorTest {

    private final StreamOutputExtractor extractor = new StreamOutputExtractor();

    @Test
    void extractPlainText_strips_text_blocks_from_assistant_drops_thinking_and_tool_use_when_result_empty() {
        String streamJson = "{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\"/tmp\"}\n"
                + "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"internal reasoning\"},"
                + "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file\":\"a.txt\"}},"
                + "{\"type\":\"text\",\"text\":\"已完成分析\"}"
                + "]}}\n"
                + "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\"巨大文件内容\"}]}}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"\"}";

        String extracted = extractor.extractPlainText(streamJson);

        assertEquals("已完成分析", extracted);
        assertFalse(extracted.contains("internal reasoning"));
        assertFalse(extracted.contains("tool_use"));
        assertFalse(extracted.contains("巨大文件内容"));
    }

    @Test
    void extractPlainText_prefers_result_field_over_accumulated_text_when_result_present() {
        String streamJson = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"中间草稿\"}]}}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"最终答复\"}";

        assertEquals("最终答复", extractor.extractPlainText(streamJson));
    }

    @Test
    void extractPlainText_returns_unchanged_when_input_is_not_stream_json() {
        String legacy = "用户的一段旧回答\n继续说明";

        assertEquals("用户的一段旧回答\n继续说明", extractor.extractPlainText(legacy));
    }

    @Test
    void extractPlainText_concatenates_multi_turn_assistant_text_when_tool_calls_interleaved() {
        String streamJson = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"先查看文件。\"}]}}\n"
                + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\"}]}}\n"
                + "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\"file body\"}]}}\n"
                + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"完成修改。\"}]}}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"\"}";

        String extracted = extractor.extractPlainText(streamJson);

        assertTrue(extracted.contains("先查看文件"));
        assertTrue(extracted.contains("完成修改"));
        assertFalse(extracted.contains("file body"));
    }

    @Test
    void extractPlainText_accumulates_content_block_delta_on_sdk_streaming() {
        String sdkStream = "{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hello \"}}\n"
                + "{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"world\"}}";

        assertEquals("Hello world", extractor.extractPlainText(sdkStream));
    }

    @Test
    void extractPlainText_ignores_invalid_json_lines_and_continues_processing() {
        String stream = "this is not json\n"
                + "{not valid json either\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"OK\"}";

        // 头两行不以 { 开头按原文保留;第二行以 { 开头但 JSON 非法,被 try/catch 吞掉;
        // 终局 result="OK" 非空,清空累积内容并以 result 为最终答复。
        assertEquals("OK", extractor.extractPlainText(stream));
    }

    @Test
    void extractPlainText_returns_empty_string_when_input_is_empty() {
        assertEquals("", extractor.extractPlainText(""));
    }

    @Test
    void extractPlainText_keeps_only_last_codex_stream_event_as_conclusion_drops_intermediate_narration_and_tool_inputs() {
        String codexStream = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"abc\"}\n"
                + "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"先查 issue-log。\"}}}\n"
                + "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\","
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"item_1\",\"name\":\"shell\"}}}\n"
                + "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\":\\\"ls\\\"}\"}}}\n"
                + "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\"巨大输出\"}]}}\n"
                + "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"诊断完成。\\n[CONCLUSION]: 已定位根因。\"}}}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"usage\":{\"input_tokens\":100}}";

        String extracted = extractor.extractPlainText(codexStream);

        // Codex 一个 turn 多条 agent_message,只留最后一条作结论;"先查 issue-log。" 是中间过程叙述
        assertEquals("诊断完成。\n[CONCLUSION]: 已定位根因。", extracted);
        assertFalse(extracted.contains("先查 issue-log"));
        assertFalse(extracted.contains("command"));
        assertFalse(extracted.contains("巨大输出"));
    }

    @Test
    void extractPlainText_should_skip_stream_event_when_assistant_lines_present_to_avoid_claude_text_doubling() {
        // Claude stream-json 同一段文字既出现在 stream_event 增量片段,也出现在 type=assistant
        // 完整消息;含 assistant 行时 stream_event 必须跳过,否则正文翻倍。result 为空不触发覆盖。
        String claudeStream = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"分析\"}}}\n"
                + "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"完成\"}}}\n"
                + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"分析完成\"}]}}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"\"}";

        assertEquals("分析完成", extractor.extractPlainText(claudeStream));
    }

    @Test
    void extractPlainText_overrides_codex_accumulated_text_with_result_field_on_turn_failure() {
        String codexStream = "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"中途进展\"}}}\n"
                + "{\"type\":\"result\",\"subtype\":\"error\","
                + "\"error\":{\"message\":\"Codex 任务失败\"},\"result\":\"Codex 任务失败\"}";

        assertEquals("Codex 任务失败", extractor.extractPlainText(codexStream));
    }
}
