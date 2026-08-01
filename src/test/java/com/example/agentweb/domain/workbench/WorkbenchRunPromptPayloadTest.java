package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Workbench Run 私有最终 Prompt 的不可变同源证明测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunPromptPayloadTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T05:00:00Z");

    @Test
    void freezeShouldComputeExactHashAndPreserveHistoryDelivery() {
        String prompt = "平台安全规则\n\n当前用户问题";

        WorkbenchRunPromptPayload payload =
                WorkbenchRunPromptPayload.freeze(
                        "run-1", prompt,
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX, NOW);

        assertEquals("run-1", payload.getRunId());
        assertEquals(prompt, payload.getFinalPrompt());
        assertEquals(CanonicalHashing.sha256(prompt), payload.getPromptHash());
        assertEquals(WorkbenchPromptHistoryDelivery.PROMPT_PREFIX,
                payload.getHistoryDelivery());
        assertEquals(NOW, payload.getCreatedAt());
    }

    @Test
    void restoreShouldRejectHashThatDoesNotMatchPrivatePrompt() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchRunPromptPayload.restore(
                        "run-1", "actual prompt",
                        CanonicalHashing.sha256("different prompt"),
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX, NOW));
    }
}
