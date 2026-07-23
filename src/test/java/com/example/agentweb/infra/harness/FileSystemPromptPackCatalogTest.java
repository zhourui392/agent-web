package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.HarnessCatalogException;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.PromptPack;
import com.example.agentweb.domain.harness.PromptResourceRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 文件系统 Prompt Pack Catalog 解析与安全边界测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class FileSystemPromptPackCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldParseManifestComputeHashAndHotDiscoverChanges() throws IOException {
        Path packDir = writePromptPack(tempDir, "analysis", HarnessStage.ANALYSIS);
        FileSystemPromptPackCatalog catalog = new FileSystemPromptPackCatalog(tempDir);

        PromptPack first = catalog.resolve(HarnessStage.ANALYSIS);
        Files.write(packDir.resolve("task.md"), "changed task".getBytes(StandardCharsets.UTF_8));
        PromptPack second = catalog.resolve(HarnessStage.ANALYSIS);

        assertEquals("analysis", first.getManifest().getId());
        assertEquals("1.0.0", first.getManifest().getVersion());
        assertEquals("task", first.resource(PromptResourceRole.TASK).getContent());
        assertEquals("changed task", second.resource(PromptResourceRole.TASK).getContent());
        assertNotEquals(first.getPackageHash(), second.getPackageHash());
    }

    @Test
    void shouldFailClosedWhenRequiredResourceIsMissing() throws IOException {
        Path packDir = writePromptPack(tempDir, "analysis", HarnessStage.ANALYSIS);
        Files.delete(packDir.resolve("gate-hints.md"));

        HarnessCatalogException error = assertThrows(HarnessCatalogException.class,
                () -> new FileSystemPromptPackCatalog(tempDir).resolve(HarnessStage.ANALYSIS));

        assertEquals("CATALOG_RESOURCE_MISSING", error.getCode());
    }

    @Test
    void shouldRejectDeclaredPathEscapingPackage() throws IOException {
        Path packDir = writePromptPack(tempDir, "analysis", HarnessStage.ANALYSIS);
        Files.write(tempDir.resolve("outside.md"), "outside".getBytes(StandardCharsets.UTF_8));
        Files.write(packDir.resolve("manifest.yml"), manifest("analysis", HarnessStage.ANALYSIS,
                "../outside.md").getBytes(StandardCharsets.UTF_8));

        HarnessCatalogException error = assertThrows(HarnessCatalogException.class,
                () -> new FileSystemPromptPackCatalog(tempDir).resolve(HarnessStage.ANALYSIS));

        assertEquals("CATALOG_PATH_ESCAPE", error.getCode());
    }

    @Test
    void shouldRejectSymbolicLinkEscapingPackage() throws IOException {
        Path packDir = writePromptPack(tempDir, "analysis", HarnessStage.ANALYSIS);
        Path outside = tempDir.resolve("outside.md");
        Files.write(outside, "outside".getBytes(StandardCharsets.UTF_8));
        Files.delete(packDir.resolve("task.md"));
        Files.createSymbolicLink(packDir.resolve("task.md"), outside);

        HarnessCatalogException error = assertThrows(HarnessCatalogException.class,
                () -> new FileSystemPromptPackCatalog(tempDir).resolve(HarnessStage.ANALYSIS));

        assertEquals("CATALOG_PATH_ESCAPE", error.getCode());
    }

    @Test
    void builtInVersionOnePromptPacksShouldCoverAllFourStages() {
        FileSystemPromptPackCatalog catalog = new FileSystemPromptPackCatalog(
                Paths.get("src/main/resources/harness/prompt-packs"));

        for (HarnessStage stage : HarnessStage.values()) {
            PromptPack pack = catalog.resolve(stage);
            assertEquals("1.0.0", pack.getManifest().getVersion());
            assertEquals(stage, pack.getManifest().getStage());
            assertEquals(4, pack.getResources().size());
        }
    }

    private Path writePromptPack(Path root, String id, HarnessStage stage) throws IOException {
        Path packDir = Files.createDirectories(root.resolve(id).resolve("1.0.0"));
        Files.write(packDir.resolve("manifest.yml"), manifest(id, stage, "task.md")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(packDir.resolve("system.md"), "system".getBytes(StandardCharsets.UTF_8));
        Files.write(packDir.resolve("task.md"), "task".getBytes(StandardCharsets.UTF_8));
        Files.write(packDir.resolve("output-contract.md"), "output".getBytes(StandardCharsets.UTF_8));
        Files.write(packDir.resolve("gate-hints.md"), "gates".getBytes(StandardCharsets.UTF_8));
        return packDir;
    }

    private String manifest(String id, HarnessStage stage, String taskPath) {
        return "schemaVersion: '1'\n"
                + "id: " + id + "\n"
                + "version: 1.0.0\n"
                + "stage: " + stage + "\n"
                + "resources:\n"
                + "  system: system.md\n"
                + "  task: " + taskPath + "\n"
                + "  outputContract: output-contract.md\n"
                + "  gateHints: gate-hints.md\n";
    }
}
