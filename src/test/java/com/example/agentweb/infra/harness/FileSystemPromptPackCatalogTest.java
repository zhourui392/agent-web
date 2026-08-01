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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 文件系统 Prompt Pack Catalog 解析与安全边界测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class FileSystemPromptPackCatalogTest {

    private static final String COMMON_RULE_ROOT =
            "src/main/resources/capability/rules";

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
    void shouldRemainCompatibleWithLegacyHarnessStageManifest() throws IOException {
        writeLegacyPromptPack(tempDir, "design", HarnessStage.DESIGN);

        PromptPack pack = new FileSystemPromptPackCatalog(tempDir)
                .resolve(HarnessStage.DESIGN);

        assertEquals("design", pack.getManifest().getId());
        assertEquals("1.0.0", pack.getManifest().getVersion());
        assertEquals(HarnessStage.DESIGN, pack.getManifest().getStage());
        assertEquals(4, pack.getResources().size());
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
        assertFalse(Files.exists(Paths.get("src/main/resources/harness/prompt-packs")));
        FileSystemPromptPackCatalog catalog = new FileSystemPromptPackCatalog(
                Paths.get(COMMON_RULE_ROOT));

        for (HarnessStage stage : HarnessStage.values()) {
            PromptPack pack = catalog.resolve(stage);
            assertEquals(stage.name().toLowerCase(java.util.Locale.ROOT),
                    pack.getManifest().getId());
            assertEquals("1.0.0", pack.getManifest().getVersion());
            assertEquals(stage, pack.getManifest().getStage());
            assertEquals(4, pack.getResources().size());
            assertEquals(legacyPackageHash(stage), pack.getPackageHash());
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

    private Path writeLegacyPromptPack(Path root, String id, HarnessStage stage)
            throws IOException {
        Path packDir = Files.createDirectories(root.resolve(id).resolve("1.0.0"));
        String legacyManifest = "schemaVersion: '1'\n"
                + "id: " + id + "\n"
                + "version: 1.0.0\n"
                + "stage: " + stage + "\n"
                + "resources:\n"
                + "  system: system.md\n"
                + "  task: task.md\n"
                + "  outputContract: output-contract.md\n"
                + "  gateHints: gate-hints.md\n";
        Files.write(packDir.resolve("manifest.yml"),
                legacyManifest.getBytes(StandardCharsets.UTF_8));
        Files.write(packDir.resolve("system.md"),
                "system".getBytes(StandardCharsets.UTF_8));
        Files.write(packDir.resolve("task.md"),
                "task".getBytes(StandardCharsets.UTF_8));
        Files.write(packDir.resolve("output-contract.md"),
                "output".getBytes(StandardCharsets.UTF_8));
        Files.write(packDir.resolve("gate-hints.md"),
                "gates".getBytes(StandardCharsets.UTF_8));
        return packDir;
    }

    private String manifest(String id, HarnessStage stage, String taskPath) {
        return "schemaVersion: '1'\n"
                + "id: " + id + "\n"
                + "version: 1.0.0\n"
                + "source: PLATFORM\n"
                + "mandatory: true\n"
                + "summary: Harness " + stage + " Prompt Pack\n"
                + "applicableUseCases:\n"
                + "  - " + stage + "\n"
                + "resources:\n"
                + "  system: system.md\n"
                + "  task: " + taskPath + "\n"
                + "  outputContract: output-contract.md\n"
                + "  gateHints: gate-hints.md\n";
    }

    private String legacyPackageHash(HarnessStage stage) {
        switch (stage) {
            case ANALYSIS:
                return "a4880a90fbbc3028954b9d054a0bb0019ef70c75c846058dca24872f07ae7074";
            case DESIGN:
                return "efd13cc5681d2eebb067cb01a8e1c705cc0e3040fbbaa8a785f845df44560af0";
            case IMPLEMENTATION:
                return "777989247ce29c6f902a1e874ef936b997a390b1064591a42b4f086c71c117b8";
            case DEPLOYMENT:
                return "47b8b953aa6bb8bc2a509d87d5ea15843eee379aba614bced8bcfd3eb698d324";
            default:
                throw new IllegalArgumentException("unsupported Harness stage: " + stage);
        }
    }
}
