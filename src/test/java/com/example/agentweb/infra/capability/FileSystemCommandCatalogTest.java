package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.example.agentweb.domain.capability.CommandDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 文件系统 Command Catalog 测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class FileSystemCommandCatalogTest {

    private static final Instant NOW = Instant.parse("2026-08-05T08:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void should_DiscoverCommands_When_OnlyConfiguredDirectoryIsEnabled() throws IOException {
        // Given
        Path enabled = Files.createDirectories(tempDir.resolve("enabled"));
        Path disabled = Files.createDirectories(tempDir.resolve("disabled"));
        writeCommand(enabled.resolve("architecture-review.md"),
                "architecture-review", "1.0.0", "架构审查", "请审查 $ARGUMENTS");
        writeCommand(disabled.resolve("hidden.md"),
                "hidden", "1.0.0", "隐藏", "hidden");
        FileSystemCommandCatalog catalog = buildCatalog(Arrays.asList(
                CommandCatalogDirectory.create("enabled", enabled.toString(), true),
                CommandCatalogDirectory.create("disabled", disabled.toString(), false)));

        // When
        List<CommandDefinition> commands = catalog.discover();

        // Then
        assertEquals(1, commands.size());
        assertEquals("architecture-review", commands.get(0).getIdentifier());
        assertEquals("enabled", commands.get(0).getSourceDirectoryIdentifier());
    }

    @Test
    void should_RejectCatalog_When_SameIdentifierVersionHasDifferentContent()
            throws IOException {
        // Given
        Path first = Files.createDirectories(tempDir.resolve("first"));
        Path second = Files.createDirectories(tempDir.resolve("second"));
        writeCommand(first.resolve("command.md"), "review", "1", "Review", "first");
        writeCommand(second.resolve("command.md"), "review", "1", "Review", "second");
        FileSystemCommandCatalog catalog = buildCatalog(Arrays.asList(
                CommandCatalogDirectory.create("first", first.toString(), true),
                CommandCatalogDirectory.create("second", second.toString(), true)));

        // When
        CapabilityCatalogException failure = assertThrows(
                CapabilityCatalogException.class, catalog::discover);

        // Then
        assertEquals("CATALOG_COMMAND_CONTENT_CONFLICT", failure.getCode());
    }

    @Test
    void should_RejectCommand_When_SymbolicLinkEscapesConfiguredDirectory()
            throws IOException {
        // Given
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path outside = tempDir.resolve("outside.md");
        writeCommand(outside, "outside", "1", "Outside", "outside");
        Files.createSymbolicLink(root.resolve("linked.md"), outside);
        FileSystemCommandCatalog catalog = buildCatalog(Collections.singletonList(
                CommandCatalogDirectory.create("commands", root.toString(), true)));

        // When
        CapabilityCatalogException failure = assertThrows(
                CapabilityCatalogException.class, catalog::discover);

        // Then
        assertEquals("CATALOG_PATH_UNSAFE", failure.getCode());
    }

    @Test
    void should_RejectCommand_When_FileExceedsSizeLimit() throws IOException {
        // Given
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.writeString(root.resolve("large.md"), "x".repeat(65537));
        FileSystemCommandCatalog catalog = buildCatalog(Collections.singletonList(
                CommandCatalogDirectory.create("commands", root.toString(), true)));

        // When
        CapabilityCatalogException failure = assertThrows(
                CapabilityCatalogException.class, catalog::discover);

        // Then
        assertEquals("CATALOG_FILE_TOO_LARGE", failure.getCode());
    }

    private FileSystemCommandCatalog buildCatalog(List<CommandCatalogDirectory> directories) {
        return new FileSystemCommandCatalog(
                directories, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void writeCommand(Path path, String identifier, String version,
                              String displayName, String prompt) throws IOException {
        Files.writeString(path, "---\n"
                + "identifier: " + identifier + "\n"
                + "version: " + version + "\n"
                + "displayName: " + displayName + "\n"
                + "description: Test command\n"
                + "argumentHint: <target>\n"
                + "---\n"
                + prompt + "\n");
    }
}
