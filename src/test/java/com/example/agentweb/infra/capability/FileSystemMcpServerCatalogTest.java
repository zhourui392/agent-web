package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.McpServerDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可信 MCP Server 文件 Catalog 的解析、Hash 和 fail-closed 测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class FileSystemMcpServerCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDiscoverStableReadOnlyServerDefinitionWithoutSecretValue() throws Exception {
        writeManifest("reader", validManifest());

        List<McpServerDefinition> definitions = new FileSystemMcpServerCatalog(tempDir).discover();

        assertEquals(1, definitions.size());
        McpServerDefinition definition = definitions.get(0);
        assertEquals("reader", definition.getId());
        assertEquals(CapabilityAccess.READ, definition.getCapabilities().get(0).getAccess());
        assertEquals(10, definition.getStartupTimeoutSeconds());
        assertEquals(30, definition.getToolTimeoutSeconds());
        assertEquals("READER_TOKEN", definition.getSecretReferences().get(0).getReference());
        assertEquals(64, definition.getConfigurationHash().length());
    }

    @Test
    void shouldParsePublicApplicableUseCasesForWorkbenchPhase() throws Exception {
        writeManifest("reader", validManifest().replace(
                "applicableUseCases: [ANALYSIS, DESIGN]\n",
                "applicableUseCases: [REVIEW_REFACTOR]\n"));

        McpServerDefinition definition =
                new FileSystemMcpServerCatalog(tempDir).discover().get(0);

        assertEquals(java.util.Collections.singleton("REVIEW_REFACTOR"),
                definition.getApplicableUseCases());
    }

    @Test
    void shouldRejectLegacyStagesManifest_WhenApplicableUseCasesAreAlsoPresent()
            throws Exception {
        writeManifest("reader", validManifest().replace(
                "applicableUseCases: [ANALYSIS, DESIGN]\n",
                "applicableUseCases: [ANALYSIS, DESIGN]\n"
                        + "stages: [ANALYSIS, DESIGN]\n"));

        CapabilityCatalogException failure = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemMcpServerCatalog(tempDir).discover());

        assertEquals("CATALOG_MANIFEST_INVALID", failure.getCode());
    }

    @Test
    void shouldRejectManifestMissingApplicableUseCases()
            throws Exception {
        writeManifest("reader", validManifest().replace(
                "applicableUseCases: [ANALYSIS, DESIGN]\n", ""));

        CapabilityCatalogException failure = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemMcpServerCatalog(tempDir).discover());

        assertEquals("CATALOG_MANIFEST_INVALID", failure.getCode());
    }

    @Test
    void malformedManifestShouldFailClosed() throws Exception {
        writeManifest("reader", validManifest().replace("access: READ", "access: UNKNOWN"));

        CapabilityCatalogException error = assertThrows(CapabilityCatalogException.class,
                () -> new FileSystemMcpServerCatalog(tempDir).discover());

        assertEquals("CATALOG_MANIFEST_INVALID", error.getCode());
    }

    @Test
    void missingIndependentToolTimeoutShouldFailClosed() throws Exception {
        writeManifest("reader", validManifest().replace("toolTimeoutSeconds: 30\n", ""));

        CapabilityCatalogException error = assertThrows(CapabilityCatalogException.class,
                () -> new FileSystemMcpServerCatalog(tempDir).discover());

        assertEquals("CATALOG_MANIFEST_INVALID", error.getCode());
    }

    @Test
    void bundledLiveFixtureShouldRemainSecretlessAndRequirementAnalysisOnly() {
        Path catalog = Paths.get(
                "src", "main", "resources", "capability", "mcp-servers");

        McpServerDefinition fixture = new FileSystemMcpServerCatalog(catalog).discover().stream()
                .filter(definition -> "local-readonly-fixture".equals(definition.getId()))
                .findFirst().orElseThrow(IllegalStateException::new);

        assertEquals("CODEX", fixture.getCompatibleRuntimes().iterator().next());
        assertEquals(CapabilityAccess.READ, fixture.getCapabilities().get(0).getAccess());
        assertTrue(fixture.getApplicableUseCases().contains("REQUIREMENT_ANALYSIS"));
        assertEquals(1, fixture.getApplicableUseCases().size());
        assertTrue(fixture.getSecretReferences().isEmpty());
        assertEquals("read_fixture", fixture.enabledReadToolNames().get(0));
        assertTrue(fixture.disabledWriteToolNames().isEmpty());
    }

    private void writeManifest(String id, String content) throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve(id).resolve("1.0.0"));
        Files.write(directory.resolve("manifest.yml"), content.getBytes(StandardCharsets.UTF_8));
    }

    private String validManifest() {
        return "schemaVersion: '1'\n"
                + "id: reader\n"
                + "version: 1.0.0\n"
                + "description: Fake read-only MCP\n"
                + "applicableUseCases: [ANALYSIS, DESIGN]\n"
                + "runtimes: [CODEX]\n"
                + "command: [fake-mcp, --stdio]\n"
                + "startupTimeoutSeconds: 10\n"
                + "toolTimeoutSeconds: 30\n"
                + "capabilities:\n"
                + "  - id: search\n"
                + "    type: TOOL\n"
                + "    access: READ\n"
                + "secrets:\n"
                + "  - environmentVariable: READER_API_KEY\n"
                + "    reference: READER_TOKEN\n";
    }
}
