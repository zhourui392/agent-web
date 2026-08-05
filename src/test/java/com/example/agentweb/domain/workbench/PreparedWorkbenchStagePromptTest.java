package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 动态 Stage Prompt 的必需部件、固定顺序和私有正文冻结测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class PreparedWorkbenchStagePromptTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    @Test
    void should_AssembleStagePartsInFixedOrder() {
        // Given
        List<WorkbenchPromptPart> reverse = new ArrayList<WorkbenchPromptPart>(
                requiredParts());
        java.util.Collections.reverse(reverse);

        // When
        PreparedWorkbenchStagePrompt prompt =
                PreparedWorkbenchStagePrompt.assemble(
                        reverse,
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX);

        // Then
        assertEquals(Arrays.asList(
                        WorkbenchPromptPartType.PLATFORM_SAFETY,
                        WorkbenchPromptPartType.ENVIRONMENT_GUARDRAIL,
                        WorkbenchPromptPartType.REPOSITORY_SCOPE,
                        WorkbenchPromptPartType.STAGE_DEFINITION,
                        WorkbenchPromptPartType.STAGE_RULES,
                        WorkbenchPromptPartType.SELECTED_CAPABILITIES,
                        WorkbenchPromptPartType.GLOBAL_CONTEXT,
                        WorkbenchPromptPartType.WORKSPACE_CONTEXT,
                        WorkbenchPromptPartType.ORIGINAL_GOAL,
                        WorkbenchPromptPartType.USER_INPUT,
                        WorkbenchPromptPartType.OUTPUT_INSTRUCTION),
                prompt.getParts().stream()
                        .map(WorkbenchPromptPart::getType).toList());
        WorkbenchRunPromptPayload payload = prompt.freezePayload(
                "run-stage-1", NOW);
        assertEquals(prompt.getPromptHash(), payload.getPromptHash());
        assertEquals(prompt.getFinalPrompt(), payload.getFinalPrompt());
    }

    @Test
    void should_RejectMissingDynamicStageRequiredPart() {
        // Given
        List<WorkbenchPromptPart> incomplete = new ArrayList<WorkbenchPromptPart>(
                requiredParts());
        incomplete.removeIf(part -> part.getType()
                == WorkbenchPromptPartType.GLOBAL_CONTEXT);

        // When / Then
        assertThrows(IllegalArgumentException.class,
                () -> PreparedWorkbenchStagePrompt.assemble(
                        incomplete,
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX));
    }

    private List<WorkbenchPromptPart> requiredParts() {
        return Arrays.asList(
                part(WorkbenchPromptPartType.PLATFORM_SAFETY),
                part(WorkbenchPromptPartType.ENVIRONMENT_GUARDRAIL),
                part(WorkbenchPromptPartType.REPOSITORY_SCOPE),
                part(WorkbenchPromptPartType.STAGE_DEFINITION),
                part(WorkbenchPromptPartType.STAGE_RULES),
                part(WorkbenchPromptPartType.SELECTED_CAPABILITIES),
                part(WorkbenchPromptPartType.GLOBAL_CONTEXT),
                part(WorkbenchPromptPartType.WORKSPACE_CONTEXT),
                part(WorkbenchPromptPartType.ORIGINAL_GOAL),
                part(WorkbenchPromptPartType.USER_INPUT),
                part(WorkbenchPromptPartType.OUTPUT_INSTRUCTION));
    }

    private WorkbenchPromptPart part(WorkbenchPromptPartType type) {
        return WorkbenchPromptPart.of(
                type, "source/" + type.name().toLowerCase(),
                "content for " + type.name());
    }
}
