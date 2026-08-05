package com.example.agentweb.app.capability;

import com.example.agentweb.app.capability.port.CapabilitySourceProbe;
import com.example.agentweb.domain.capability.CapabilityConfigurationEditor;
import com.example.agentweb.domain.capability.CapabilitySourceConfiguration;
import com.example.agentweb.domain.capability.CapabilitySourceConfigurationRepository;
import com.example.agentweb.domain.capability.CapabilitySourceVersionConflictException;
import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.example.agentweb.domain.capability.SkillCatalogDirectory;
import com.example.agentweb.domain.capability.SkillTrustSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Capability Source Configuration 应用编排测试。
 *
 * @author alex
 * @since 2026-08-05
 */
@ExtendWith(MockitoExtension.class)
class CapabilitySourceConfigurationAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T08:00:00Z");
    private static final CapabilityConfigurationEditor EDITOR =
            CapabilityConfigurationEditor.create("admin-1", "Alex");

    @Mock
    private CapabilitySourceConfigurationRepository repository;

    @Mock
    private CapabilitySourceProbe probe;

    private CapabilitySourceConfigurationAppService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new CapabilitySourceConfigurationAppService(repository, probe, clock);
    }

    @Test
    void should_CreateFirstConfiguration_When_ExpectedVersionIsZero() {
        // Given
        CapabilitySourceCandidate candidate = buildCandidate("commands");
        CapabilitySourceProbeResult probed = buildProbeResult("commands");
        when(probe.probe(candidate)).thenReturn(probed);
        when(repository.find()).thenReturn(Optional.empty());

        // When
        CapabilitySourceConfiguration created = service.update(candidate, 0L, EDITOR);

        // Then
        assertEquals(1L, created.getVersion());
        verify(probe).probe(candidate);
        verify(repository).save(created, 0L);
    }

    @Test
    void should_UpdateExistingConfiguration_When_ExpectedVersionMatches() {
        // Given
        CapabilitySourceCandidate candidate = buildCandidate("new-commands");
        CapabilitySourceProbeResult probed = buildProbeResult("new-commands");
        CapabilitySourceConfiguration current = buildConfiguration("commands");
        when(probe.probe(candidate)).thenReturn(probed);
        when(repository.find()).thenReturn(Optional.of(current));

        // When
        CapabilitySourceConfiguration updated = service.update(candidate, 1L, EDITOR);

        // Then
        assertEquals(2L, updated.getVersion());
        assertEquals("new-commands", updated.getCommandCatalogDirectories()
                .get(0).getDirectoryIdentifier());
        verify(repository).save(updated, 1L);
    }

    @Test
    void should_NotPersistConfiguration_When_SourceProbeFails() {
        // Given
        CapabilitySourceCandidate candidate = buildCandidate("commands");
        when(probe.probe(candidate)).thenThrow(new IllegalArgumentException("invalid source"));

        // When / Then
        assertThrows(IllegalArgumentException.class,
                () -> service.update(candidate, 0L, EDITOR));
        verifyNoInteractions(repository);
    }

    @Test
    void should_RejectCreate_When_ExpectedVersionIsNotZero() {
        // Given
        CapabilitySourceCandidate candidate = buildCandidate("commands");
        when(probe.probe(candidate)).thenReturn(buildProbeResult("commands"));
        when(repository.find()).thenReturn(Optional.empty());

        // When / Then
        assertThrows(CapabilitySourceVersionConflictException.class,
                () -> service.update(candidate, 1L, EDITOR));
        verify(repository, never()).save(any(), any(Long.class));
    }

    @Test
    void should_ReturnStoredConfiguration_When_QueryingCurrentSettings() {
        // Given
        CapabilitySourceConfiguration current = buildConfiguration("commands");
        when(repository.find()).thenReturn(Optional.of(current));

        // When
        Optional<CapabilitySourceConfiguration> result = service.find();

        // Then
        assertSame(current, result.orElseThrow());
        verify(repository).find();
    }

    private CapabilitySourceCandidate buildCandidate(String identifier) {
        return new CapabilitySourceCandidate(
                Collections.singletonList(CommandCatalogDirectory.create(
                        identifier, "/opt/agent/" + identifier, true)),
                Collections.emptyList(), emptyMcp());
    }

    private CapabilitySourceProbeResult buildProbeResult(String identifier) {
        return new CapabilitySourceProbeResult(
                Collections.singletonList(CommandCatalogDirectory.create(
                        identifier, "/opt/agent/" + identifier, true)),
                Collections.<SkillCatalogDirectory>emptyList(), emptyMcp(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    private CapabilitySourceConfiguration buildConfiguration(String identifier) {
        return CapabilitySourceConfiguration.create(
                Collections.singletonList(CommandCatalogDirectory.create(
                        identifier, "/opt/agent/" + identifier, true)),
                Collections.<SkillCatalogDirectory>emptyList(), emptyMcp(), EDITOR, NOW);
    }

    private String emptyMcp() {
        return "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[]}";
    }
}
