package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilityAccess;
import com.example.agentweb.domain.harness.HarnessCatalogException;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.McpServerDefinition;
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
 * @author zhourui(V33215020)
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
    void malformedManifestShouldFailClosed() throws Exception {
        writeManifest("reader", validManifest().replace("access: READ", "access: UNKNOWN"));

        HarnessCatalogException error = assertThrows(HarnessCatalogException.class,
                () -> new FileSystemMcpServerCatalog(tempDir).discover());

        assertEquals("CATALOG_MANIFEST_INVALID", error.getCode());
    }

    @Test
    void missingIndependentToolTimeoutShouldFailClosed() throws Exception {
        writeManifest("reader", validManifest().replace("toolTimeoutSeconds: 30\n", ""));

        HarnessCatalogException error = assertThrows(HarnessCatalogException.class,
                () -> new FileSystemMcpServerCatalog(tempDir).discover());

        assertEquals("CATALOG_MANIFEST_INVALID", error.getCode());
    }

    @Test
    void bundledLiveFixtureShouldRemainSecretlessAndAnalysisOnly() {
        Path catalog = Paths.get("src", "main", "resources", "harness", "mcp-servers");

        McpServerDefinition fixture = new FileSystemMcpServerCatalog(catalog).discover().stream()
                .filter(definition -> "local-readonly-fixture".equals(definition.getId()))
                .findFirst().orElseThrow(IllegalStateException::new);

        assertEquals(AgentRuntime.CODEX, fixture.getRuntimes().iterator().next());
        assertEquals(CapabilityAccess.READ, fixture.getCapabilities().get(0).getAccess());
        assertTrue(fixture.getApplicableStages().contains(HarnessStage.ANALYSIS));
        assertEquals(1, fixture.getApplicableStages().size());
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
                + "stages: [ANALYSIS, DESIGN]\n"
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
