package com.example.agentweb.domain.capability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Capability 来源配置领域测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class CapabilitySourceConfigurationTest {

    @Test
    void should_CreateCanonicalConfiguration_When_SourcesAreValid() {
        // Given
        Instant now = Instant.parse("2026-08-05T08:00:00Z");

        // When
        CapabilitySourceConfiguration configuration = CapabilitySourceConfiguration.create(
                Collections.singletonList(CommandCatalogDirectory.create(
                        "platform-commands", "/opt/agent/commands/../commands", true)),
                Collections.singletonList(SkillCatalogDirectory.create(
                        "approved-skills", "/opt/agent/skills", SkillTrustSource.PLATFORM, true)),
                "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[]}",
                CapabilityConfigurationEditor.create("admin-1", "Alex"), now);

        // Then
        assertEquals(1L, configuration.getVersion());
        assertEquals("/opt/agent/commands",
                configuration.getCommandCatalogDirectories().get(0).getAbsoluteDirectory());
        assertEquals(64, configuration.getConfigurationHash().length());
        assertEquals("admin-1", configuration.getUpdatedBy().getActorId());
    }

    @Test
    void should_KeepConfigurationHashStable_When_OnlyAuditFactsChange() {
        // Given
        CapabilitySourceConfiguration first = buildConfiguration(
                "admin-1", Instant.parse("2026-08-05T08:00:00Z"));
        CapabilitySourceConfiguration second = buildConfiguration(
                "admin-2", Instant.parse("2026-08-05T09:00:00Z"));

        // When
        String firstHash = first.getConfigurationHash();
        String secondHash = second.getConfigurationHash();

        // Then
        assertEquals(firstHash, secondHash);
    }

    @Test
    void should_RejectDuplicateAndSharedDirectories_When_SourcesConflict() {
        // Given
        CommandCatalogDirectory first = CommandCatalogDirectory.create(
                "commands-a", "/opt/agent/commands", true);
        CommandCatalogDirectory duplicatePath = CommandCatalogDirectory.create(
                "commands-b", "/opt/agent/commands", true);
        SkillCatalogDirectory shared = SkillCatalogDirectory.create(
                "skills", "/opt/agent/commands", SkillTrustSource.PLATFORM, true);

        // When / Then
        assertThrows(IllegalArgumentException.class, () -> CapabilitySourceConfiguration.create(
                Arrays.asList(first, duplicatePath), Collections.emptyList(), emptyMcp(),
                editor(), Instant.parse("2026-08-05T08:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> CapabilitySourceConfiguration.create(
                Collections.singletonList(first), Collections.singletonList(shared), emptyMcp(),
                editor(), Instant.parse("2026-08-05T08:00:00Z")));
    }

    @Test
    void should_UpdateAtomically_When_ExpectedVersionMatches() {
        // Given
        CapabilitySourceConfiguration current = buildConfiguration(
                "admin-1", Instant.parse("2026-08-05T08:00:00Z"));
        SkillCatalogDirectory additional = SkillCatalogDirectory.create(
                "approved", "/opt/agent/approved-skills",
                SkillTrustSource.APPROVED_USER, true);

        // When
        CapabilitySourceConfiguration updated = current.update(1L,
                current.getCommandCatalogDirectories(),
                Collections.singletonList(additional), emptyMcp(),
                CapabilityConfigurationEditor.create("admin-2", "Blair"),
                Instant.parse("2026-08-05T09:00:00Z"));

        // Then
        assertEquals(2L, updated.getVersion());
        assertNotEquals(current.getConfigurationHash(), updated.getConfigurationHash());
        assertEquals("admin-2", updated.getUpdatedBy().getActorId());
    }

    @Test
    void should_RejectUpdate_When_ExpectedVersionIsStale() {
        // Given
        CapabilitySourceConfiguration current = buildConfiguration(
                "admin-1", Instant.parse("2026-08-05T08:00:00Z"));

        // When
        CapabilitySourceVersionConflictException failure = assertThrows(
                CapabilitySourceVersionConflictException.class,
                () -> current.update(0L, current.getCommandCatalogDirectories(),
                        current.getSkillCatalogDirectories(), emptyMcp(),
                        editor(), Instant.parse("2026-08-05T09:00:00Z")));

        // Then
        assertEquals("WORKBENCH_CAPABILITY_SOURCE_VERSION_CONFLICT", failure.getCode());
    }

    private CapabilitySourceConfiguration buildConfiguration(String actor, Instant time) {
        return CapabilitySourceConfiguration.create(
                Collections.singletonList(CommandCatalogDirectory.create(
                        "platform-commands", "/opt/agent/commands", true)),
                Collections.singletonList(SkillCatalogDirectory.create(
                        "platform-skills", "/opt/agent/skills", SkillTrustSource.PLATFORM, true)),
                emptyMcp(), CapabilityConfigurationEditor.create(actor, actor), time);
    }

    private CapabilityConfigurationEditor editor() {
        return CapabilityConfigurationEditor.create("admin-1", "Alex");
    }

    private String emptyMcp() {
        return "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[]}";
    }
}
