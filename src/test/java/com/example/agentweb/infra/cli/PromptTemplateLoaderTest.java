package com.example.agentweb.infra.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PromptTemplateLoader} 单测。
 *
 * @author zhourui(V33215020)
 * @since 2026-05-24
 */
public class PromptTemplateLoaderTest {

    @Test
    public void config_with_classpath_prefix_should_strip_prefix_and_load() {
        String text = PromptTemplateLoader.load(
                "classpath:/issue-log-refine-prompt.md",
                "/non-existent-default.md",
                "fallback");

        assertTrue(text.length() > 0, "应成功加载真实模板");
        assertTrue(!text.equals("fallback"), "应使用配置而非 fallback");
    }

    @Test
    public void config_is_null_should_use_default_resource() {
        String text = PromptTemplateLoader.load(
                null,
                "/issue-log-refine-prompt.md",
                "fallback");

        assertTrue(text.length() > 0);
        assertTrue(!text.equals("fallback"));
    }

    @Test
    public void config_without_classpath_prefix_should_use_default_resource() {
        String text = PromptTemplateLoader.load(
                "some-other-format",
                "/issue-log-refine-prompt.md",
                "fallback");

        assertTrue(text.length() > 0);
        assertTrue(!text.equals("fallback"));
    }

    @Test
    public void resource_not_found_should_fall_back_to_inline_fallback() {
        String text = PromptTemplateLoader.load(
                "classpath:/does-not-exist.md",
                "/also-does-not-exist.md",
                "inline-fallback-text");

        assertEquals("inline-fallback-text", text);
    }

    @Test
    public void default_resource_also_missing_when_config_absent_should_still_fall_back_to_inline_fallback() {
        String text = PromptTemplateLoader.load(
                null,
                "/missing-resource.md",
                "inline-fallback-text");

        assertEquals("inline-fallback-text", text);
    }
}
