package com.example.agentweb.infra.cli;

import com.example.agentweb.app.agentrun.LlmJsonExtractor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link LlmJsonExtractor} 单测。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-24
 */
public class LlmJsonExtractorTest {

    @Test
    public void pure_json_object_should_return_as_is() {
        String json = "{\"k\":\"v\"}";
        assertEquals(json, LlmJsonExtractor.extractFirstObject(json));
    }

    @Test
    public void code_block_wrapped_should_strip_fence() {
        String wrapped = "```json\n{\"k\":\"v\"}\n```";
        assertEquals("{\"k\":\"v\"}", LlmJsonExtractor.extractFirstObject(wrapped));
    }

    @Test
    public void surrounded_by_explanatory_text_should_extract_first_json() {
        String mixed = "我来生成:\n{\"k\":\"v\"}\n希望对您有帮助。";
        assertEquals("{\"k\":\"v\"}", LlmJsonExtractor.extractFirstObject(mixed));
    }

    @Test
    public void nested_braces_should_take_outermost_complete_object() {
        String nested = "{\"outer\":{\"inner\":1}}";
        assertEquals(nested, LlmJsonExtractor.extractFirstObject(nested));
    }

    @Test
    public void braces_inside_string_should_not_misjudge_as_nested() {
        String tricky = "{\"text\":\"}{\"}";
        assertEquals(tricky, LlmJsonExtractor.extractFirstObject(tricky));
    }

    @Test
    public void string_escape_should_keep_state_machine_correct() {
        String escaped = "{\"text\":\"a\\\"b\"}";
        assertEquals(escaped, LlmJsonExtractor.extractFirstObject(escaped));
    }

    @Test
    public void null_input_throws_illegal_argument_exception() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmJsonExtractor.extractFirstObject(null));
    }

    @Test
    public void no_braces_throws_illegal_argument_exception() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmJsonExtractor.extractFirstObject("纯文本没有 JSON"));
    }

    @Test
    public void unclosed_braces_throws_illegal_argument_exception() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmJsonExtractor.extractFirstObject("{\"k\":\"v\""));
    }
}
