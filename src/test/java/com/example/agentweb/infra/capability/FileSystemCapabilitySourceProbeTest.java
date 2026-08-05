package com.example.agentweb.infra.capability;

import com.example.agentweb.app.capability.CapabilitySourceCandidate;
import com.example.agentweb.app.capability.CapabilitySourceProbeResult;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Capability Source 文件系统探测测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class FileSystemCapabilitySourceProbeTest {

    @TempDir
    Path tempDir;

    @Test
    void should_ReturnCanonicalSourcesAndDiscovery_When_CandidateIsValid()
            throws IOException {
        // Given
        Path commands = Files.createDirectories(tempDir.resolve("commands"));
        Files.writeString(commands.resolve("review.md"), "---\n"
                + "identifier: review\nversion: 1\ndisplayName: Review\n"
                + "description: Review code\nargumentHint: <target>\n---\n"
                + "Review $ARGUMENTS\n");
        CapabilitySourceCandidate candidate = new CapabilitySourceCandidate(
                Collections.singletonList(CommandCatalogDirectory.create(
                        "commands", commands.toString(), true)),
                Collections.emptyList(), emptyMcp());
        FileSystemCapabilitySourceProbe probe = buildProbe();

        // When
        CapabilitySourceProbeResult result = probe.probe(candidate);

        // Then
        assertEquals(commands.toRealPath().toString(),
                result.getCommandCatalogDirectories().get(0).getAbsoluteDirectory());
        assertEquals(1, result.getCommands().size());
        assertEquals("review", result.getCommands().get(0).getIdentifier());
        assertEquals(emptyMcp(), result.getCanonicalMcpConfigurationJson());
    }

    @Test
    void should_RejectCandidate_When_ConfiguredRootContainsSymbolicLink()
            throws IOException {
        // Given
        Path real = Files.createDirectories(tempDir.resolve("real"));
        Path linked = tempDir.resolve("linked");
        Files.createSymbolicLink(linked, real);
        CapabilitySourceCandidate candidate = new CapabilitySourceCandidate(
                Collections.singletonList(CommandCatalogDirectory.create(
                        "commands", linked.toString(), true)),
                Collections.emptyList(), emptyMcp());

        // When
        CapabilityCatalogException failure = assertThrows(
                CapabilityCatalogException.class, () -> buildProbe().probe(candidate));

        // Then
        assertEquals("CATALOG_PATH_UNSAFE", failure.getCode());
    }

    private FileSystemCapabilitySourceProbe buildProbe() {
        return new FileSystemCapabilitySourceProbe(new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneOffset.UTC));
    }

    private String emptyMcp() {
        return "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[]}";
    }
}
