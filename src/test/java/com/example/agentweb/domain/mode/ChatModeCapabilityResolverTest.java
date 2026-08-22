package com.example.agentweb.domain.mode;

import com.example.agentweb.domain.capability.CapabilityArtifactRegistry;
import com.example.agentweb.domain.capability.CapabilityResolutionException;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatModeCapabilityResolver 快照重验信任链回归。
 *
 * @author alex
 * @since 2026-08-20
 */
class ChatModeCapabilityResolverTest {

    private final CapabilityArtifactRegistry registry =
            mock(CapabilityArtifactRegistry.class);
    private final ChatModeCapabilityResolver resolver =
            new ChatModeCapabilityResolver(registry);

    private static CommandDefinition command(String identifier, String content) {
        return CommandDefinition.create(identifier, "1", identifier, "描述",
                null, content, "source", Instant.now());
    }

    @Test
    void resolvesBindingAndCommandContent() {
        CommandDefinition definition = command("cmd", "做点什么 $ARGUMENTS");
        when(registry.requireCommand("cmd", "1", definition.getContentHash()))
                .thenReturn(definition);
        ModeSnapshot snapshot = new ModeSnapshot("m1", "reviewer", "评审", null, null,
                null, null, null,
                List.of(new ModeCapabilities.CommandRef("cmd", "1",
                        definition.getContentHash(), 0)),
                null, null);
        ResolvedModeCapabilities resolved = resolver.resolve(
                snapshot, AgentType.CLAUDE, "matrix@1");
        assertEquals(1, resolved.getCommands().size());
        assertEquals("做点什么 $ARGUMENTS",
                resolved.getCommands().get(0).getPromptTemplate());
        assertEquals(0, resolved.getBinding().getSkills().size());
    }

    @Test
    void failsClosedWhenRegistryRejectsStaleHash() {
        when(registry.requireCommand(anyString(), anyString(), anyString()))
                .thenThrow(new CapabilityResolutionException(
                        "CAPABILITY_NOT_FOUND", "archived"));
        ModeSnapshot snapshot = new ModeSnapshot("m1", "reviewer", "评审", null, null,
                null, null, null,
                List.of(new ModeCapabilities.CommandRef("cmd", "1",
                        ChatModeTest.hashOf("stale"), 0)),
                null, null);
        assertThrows(CapabilityResolutionException.class, () ->
                resolver.resolve(snapshot, AgentType.CLAUDE, "matrix@1"));
    }

    @Test
    void requiresCompleteFacts() {
        ModeSnapshot snapshot = new ModeSnapshot("m1", "reviewer", "评审", null,
                null, null, null, null, null, null, null);
        assertThrows(NullPointerException.class, () ->
                resolver.resolve(snapshot, null, "matrix@1"));
        assertThrows(NullPointerException.class, () ->
                resolver.resolve(null, AgentType.CLAUDE, "matrix@1"));
    }
}
