package com.example.agentweb.app;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@link IssueLogWriter#extractPlainText(String)} 对两种 CLI 输出形态的剥离行为，
 * 确保摘要流水线不会把 tool_use / tool_result / thinking 等噪音块喂给下游 CLI。
 */
public class IssueLogWriterExtractTest {

    private final IssueLogWriter writer = newWriter();

    @Test
    void extractsTextBlocksFromStreamJsonAssistantLine() throws Exception {
        String streamJson = "{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\"/tmp\"}\n"
                + "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"thinking\",\"thinking\":\"internal reasoning\"},"
                + "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file\":\"a.txt\"}},"
                + "{\"type\":\"text\",\"text\":\"已完成分析\"}"
                + "]}}\n"
                + "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\"巨大文件内容\"}]}}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"\"}";

        String extracted = invokeExtract(streamJson);

        assertEquals("已完成分析", extracted);
        assertFalse(extracted.contains("internal reasoning"));
        assertFalse(extracted.contains("tool_use"));
        assertFalse(extracted.contains("巨大文件内容"));
    }

    @Test
    void resultLineOverridesAccumulatedWhenNonEmpty() throws Exception {
        String streamJson = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"中间草稿\"}]}}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"最终答复\"}";

        assertEquals("最终答复", invokeExtract(streamJson));
    }

    @Test
    void keepsLegacyPlainTextAsIs() throws Exception {
        String legacy = "用户的一段旧回答\n继续说明";

        assertEquals("用户的一段旧回答\n继续说明", invokeExtract(legacy));
    }

    @Test
    void concatenatesMultipleAssistantTurnsBetweenToolCalls() throws Exception {
        String streamJson = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"先查看文件。\"}]}}\n"
                + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Read\"}]}}\n"
                + "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\"file body\"}]}}\n"
                + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"完成修改。\"}]}}\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"\"}";

        String extracted = invokeExtract(streamJson);

        assertTrue(extracted.contains("先查看文件"));
        assertTrue(extracted.contains("完成修改"));
        assertFalse(extracted.contains("file body"));
    }

    private static IssueLogWriter newWriter() {
        try {
            Constructor<IssueLogWriter> ctor = IssueLogWriter.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private String invokeExtract(String raw) throws ReflectiveOperationException {
        Method m = IssueLogWriter.class.getDeclaredMethod("extractPlainText", String.class);
        m.setAccessible(true);
        return (String) m.invoke(writer, raw);
    }
}
