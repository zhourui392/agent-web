package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.DeploymentCommandTemplate;
import com.example.agentweb.domain.harness.DeploymentStep;
import com.example.agentweb.domain.harness.HarnessCatalogException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 管理员部署模板 Catalog 的安全 YAML、热读取和路径边界测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class FileSystemDeploymentCommandTemplateCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadTokenizedLocalTemplateAndObserveUpdates() throws Exception {
        Path manifest = Files.createDirectories(tempDir.resolve("local-default"))
                .resolve("manifest.yml");
        Files.write(manifest, yaml("1.0.0").getBytes(StandardCharsets.UTF_8));
        FileSystemDeploymentCommandTemplateCatalog catalog =
                new FileSystemDeploymentCommandTemplateCatalog(tempDir);

        DeploymentCommandTemplate first = catalog.resolve("local-default");
        Files.write(manifest, yaml("1.0.1").getBytes(StandardCharsets.UTF_8));
        DeploymentCommandTemplate updated = catalog.resolve("local-default");

        assertEquals("1.0.0", first.getVersion());
        assertEquals("1.0.1", updated.getVersion());
        assertEquals("safe-runner", updated.command(DeploymentStep.BUILD).getArguments().get(0));
    }

    @Test
    void shouldRejectProductionOrUnknownTemplate() throws Exception {
        Path manifest = Files.createDirectories(tempDir.resolve("unsafe"))
                .resolve("manifest.yml");
        Files.write(manifest, yaml("1.0.0").replace("environment: local",
                "environment: production").getBytes(StandardCharsets.UTF_8));
        FileSystemDeploymentCommandTemplateCatalog catalog =
                new FileSystemDeploymentCommandTemplateCatalog(tempDir);

        assertThrows(HarnessCatalogException.class, () -> catalog.resolve("unsafe"));
        assertThrows(HarnessCatalogException.class, () -> catalog.resolve("missing"));
    }

    private String yaml(String version) {
        return "schemaVersion: '1'\n"
                + "id: local-default\n"
                + "version: " + version + "\n"
                + "environment: local\n"
                + "commands:\n"
                + "  build: [safe-runner, build]\n"
                + "  deploy: [safe-runner, deploy]\n"
                + "  healthCheck: [safe-runner, health]\n"
                + "  acceptance: [safe-runner, acceptance]\n"
                + "  rollback: [safe-runner, rollback]\n";
    }
}
