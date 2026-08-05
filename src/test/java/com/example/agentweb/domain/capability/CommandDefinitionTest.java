package com.example.agentweb.domain.capability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Command Definition 领域测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class CommandDefinitionTest {

    @Test
    void should_ExpandArgumentsAndCreateBinding_When_TemplateIsValid() {
        // Given
        CommandDefinition command = CommandDefinition.create(
                "architecture-review", "1.0.0", "架构审查",
                "审查聚合边界", "<目标>", "请审查：$ARGUMENTS",
                "platform-commands", java.time.Instant.parse("2026-08-05T08:00:00Z"));

        // When
        ResolvedCommandBinding binding = command.resolve(
                command.getContentHash(), "Workbench Stage Catalog");

        // Then
        assertEquals("请审查：Workbench Stage Catalog", binding.getExpandedPrompt());
        assertEquals(command.getContentHash(), binding.getContentHash());
        assertEquals(64, binding.getExpandedPromptHash().length());
    }

    @Test
    void should_RejectUnknownPlaceholder_When_CommandIsCreated() {
        // Given / When / Then
        CommandResolutionException failure = assertThrows(
                CommandResolutionException.class,
                () -> CommandDefinition.create("unsafe", "1", "Unsafe", "Unsafe",
                        "", "run $WORKSPACE", "commands",
                        java.time.Instant.parse("2026-08-05T08:00:00Z")));
        assertEquals("WORKBENCH_COMMAND_TEMPLATE_INVALID", failure.getCode());
    }

    @Test
    void should_RejectExpansion_When_ExpandedPromptExceedsLimit() {
        // Given
        CommandDefinition command = CommandDefinition.create(
                "large", "1", "Large", "Large", "", "$ARGUMENTS", "commands",
                java.time.Instant.parse("2026-08-05T08:00:00Z"));
        String arguments = "x".repeat(CommandDefinition.MAX_EXPANDED_PROMPT_LENGTH + 1);

        // When
        CommandResolutionException failure = assertThrows(
                CommandResolutionException.class,
                () -> command.resolve(command.getContentHash(), arguments));

        // Then
        assertEquals("WORKBENCH_COMMAND_EXPANSION_TOO_LARGE", failure.getCode());
    }
}
