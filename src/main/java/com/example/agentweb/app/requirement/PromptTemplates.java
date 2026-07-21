package com.example.agentweb.app.requirement;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 需求线 prompt 模板加载与占位替换（{{key}} 形式，对齐 refinery prompt 文件模式）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /**
     * 加载 classpath 模板并替换占位符。
     *
     * @param classpathLocation 如 "/requirement-plan-prompt.md"
     * @param values            占位符名 → 值（null 值按空串替换）
     * @return 渲染后的 prompt
     */
    public static String render(String classpathLocation, Map<String, String> values) {
        String template = load(classpathLocation);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            template = template.replace("{{" + entry.getKey() + "}}", value);
        }
        return template;
    }

    private static String load(String classpathLocation) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(classpathLocation).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("prompt 模板加载失败: " + classpathLocation, e);
        }
    }
}
