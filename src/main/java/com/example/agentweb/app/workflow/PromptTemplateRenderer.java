package com.example.agentweb.app.workflow;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流 prompt 模板渲染器,仅支持简单占位符替换。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Component
public class PromptTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final String INPUT_PREFIX = "inputs.";
    private static final String STEP_PREFIX = "steps.";
    private static final String STEP_OUTPUT_SUFFIX = ".output";

    /**
     * 渲染 prompt 模板。
     *
     * @param template prompt 模板
     * @param inputs 运行输入
     * @param stepOutputs 已完成步骤输出
     * @return 渲染结果
     */
    public RenderResult render(String template, Map<String, Object> inputs, Map<String, String> stepOutputs) {
        if (template == null) {
            return new RenderResult("", Collections.singletonList("template is null"));
        }
        Map<String, Object> safeInputs = inputs == null ? Collections.emptyMap() : inputs;
        Map<String, String> safeStepOutputs = stepOutputs == null ? Collections.emptyMap() : stepOutputs;
        List<String> warnings = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String value = resolve(expression, safeInputs, safeStepOutputs);
            if (value == null) {
                warnings.add("missing variable: " + expression);
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
            }
        }
        matcher.appendTail(buffer);
        return new RenderResult(buffer.toString(), warnings);
    }

    private String resolve(String expression, Map<String, Object> inputs, Map<String, String> stepOutputs) {
        if (expression.startsWith(INPUT_PREFIX)) {
            Object value = inputs.get(expression.substring(INPUT_PREFIX.length()));
            return value == null ? null : String.valueOf(value);
        }
        if (expression.startsWith(STEP_PREFIX) && expression.endsWith(STEP_OUTPUT_SUFFIX)) {
            String name = expression.substring(STEP_PREFIX.length(),
                    expression.length() - STEP_OUTPUT_SUFFIX.length());
            return stepOutputs.get(name);
        }
        return null;
    }

    /**
     * 模板渲染结果。
     *
     * @author zhourui(V33215020)
     * @since 2026-06-12
     */
    @Getter
    public static class RenderResult {

        private final String text;
        private final List<String> warnings;

        /**
         * 创建渲染结果。
         *
         * @param text 渲染后的文本
         * @param warnings 缺失变量等 warning
         */
        public RenderResult(String text, List<String> warnings) {
            this.text = text;
            this.warnings = warnings == null ? Collections.emptyList() : new ArrayList<>(warnings);
        }
    }
}
