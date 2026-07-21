package com.example.agentweb.app.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@DisplayName("工作流 Prompt 模板渲染测试")
class PromptTemplateRendererTest {

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();

    @Test
    @DisplayName("正常场景 - 替换运行输入变量")
    void should_ReplaceInputVariable_When_InputExists() {
        // Given
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("branch", "feature/workflow-poc");

        // When
        PromptTemplateRenderer.RenderResult result = renderer.render(
                "Review {{inputs.branch}}", inputs, Collections.emptyMap());

        // Then
        assertEquals("Review feature/workflow-poc", result.getText());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("正常场景 - 替换已完成步骤输出")
    void should_ReplaceStepOutput_When_StepCompleted() {
        // Given
        Map<String, String> stepOutputs = new HashMap<>();
        stepOutputs.put("review", "发现 2 个问题");

        // When
        PromptTemplateRenderer.RenderResult result = renderer.render(
                "Summarize {{steps.review.output}}", Collections.emptyMap(), stepOutputs);

        // Then
        assertEquals("Summarize 发现 2 个问题", result.getText());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("异常场景 - 缺失变量保留原占位符并记录 warning")
    void should_KeepPlaceholderAndWarn_When_VariableMissing() {
        // When
        PromptTemplateRenderer.RenderResult result = renderer.render(
                "Review {{inputs.branch}} and {{steps.review.output}}",
                Collections.emptyMap(), Collections.emptyMap());

        // Then
        assertEquals("Review {{inputs.branch}} and {{steps.review.output}}", result.getText());
        assertEquals(2, result.getWarnings().size());
    }
}
