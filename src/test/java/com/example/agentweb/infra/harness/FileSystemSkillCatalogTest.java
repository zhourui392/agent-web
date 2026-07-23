package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilityGrant;
import com.example.agentweb.domain.harness.CapabilityKind;
import com.example.agentweb.domain.harness.CapabilitySelectionRequest;
import com.example.agentweb.domain.harness.HarnessCatalogException;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.SkillPackage;
import com.example.agentweb.domain.harness.SkillSelection;
import com.example.agentweb.domain.harness.SkillSelectionPolicy;
import com.example.agentweb.domain.harness.SkillTrustSource;
import com.example.agentweb.domain.harness.StageCapabilityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 文件系统 Skill Catalog Manifest、Package Hash、热发现和路径安全测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class FileSystemSkillCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldParseSkillAndHashManifestEntryAndDeclaredResources() throws IOException {
        Path skillDir = writeSkill(tempDir, "java-review", "references/rules.md");
        FileSystemSkillCatalog catalog = new FileSystemSkillCatalog(tempDir, SkillTrustSource.PLATFORM);

        SkillPackage first = catalog.discover().get(0);
        Files.write(skillDir.resolve("references/rules.md"), "changed rules".getBytes(StandardCharsets.UTF_8));
        SkillPackage second = catalog.discover().get(0);

        assertEquals("java-review", first.getManifest().getId());
        assertEquals(SkillTrustSource.PLATFORM, first.getManifest().getTrustSource());
        assertEquals(CapabilityKind.COMMAND,
                first.getManifest().getCapabilityRequests().get(0).getKind());
        assertEquals("# Java review", first.getEntryContent());
        assertNotEquals(first.getPackageHash(), second.getPackageHash());
    }

    @Test
    void shouldHotDiscoverNewPackageWithoutRestart() throws IOException {
        writeSkill(tempDir, "first", "references/rules.md");
        FileSystemSkillCatalog catalog = new FileSystemSkillCatalog(tempDir, SkillTrustSource.PLATFORM);
        assertEquals(1, catalog.discover().size());

        writeSkill(tempDir, "second", "references/rules.md");

        assertEquals(2, catalog.discover().size());
    }

    @Test
    void shouldNotReadOrHashCliNativeInstructionFiles() throws IOException {
        Path skillDir = writeSkill(tempDir, "java-review", "references/rules.md");
        FileSystemSkillCatalog catalog = new FileSystemSkillCatalog(tempDir, SkillTrustSource.PLATFORM);
        SkillPackage first = catalog.discover().get(0);
        Files.write(skillDir.resolve("AGENTS.md"), "do not inject".getBytes(StandardCharsets.UTF_8));
        Files.write(skillDir.resolve("CLAUDE.md"), "do not inject".getBytes(StandardCharsets.UTF_8));

        SkillPackage second = catalog.discover().get(0);

        assertEquals(first.getPackageHash(), second.getPackageHash());
        assertFalse(second.getResourceHashes().containsKey("AGENTS.md"));
        assertFalse(second.getResourceHashes().containsKey("CLAUDE.md"));
    }

    @Test
    void shouldRejectResourcePathEscapeAndForgedTrustSource() throws IOException {
        writeSkill(tempDir, "escaping", "../outside.md");
        Files.write(tempDir.resolve("escaping/outside.md"), "outside".getBytes(StandardCharsets.UTF_8));

        HarnessCatalogException pathError = assertThrows(HarnessCatalogException.class,
                () -> new FileSystemSkillCatalog(tempDir, SkillTrustSource.PLATFORM).discover());
        assertEquals("CATALOG_PATH_ESCAPE", pathError.getCode());

        Path trustedRoot = Files.createDirectory(tempDir.resolve("trusted"));
        writeSkill(trustedRoot, "forged", "references/rules.md");
        Path manifest = trustedRoot.resolve("forged/1.0.0/manifest.yml");
        String content = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)
                .replace("trustSource: PLATFORM", "trustSource: WORKSPACE");
        Files.write(manifest, content.getBytes(StandardCharsets.UTF_8));

        HarnessCatalogException trustError = assertThrows(HarnessCatalogException.class,
                () -> new FileSystemSkillCatalog(trustedRoot, SkillTrustSource.PLATFORM).discover());
        assertEquals("CATALOG_TRUST_SOURCE_MISMATCH", trustError.getCode());
    }

    @Test
    void builtInCatalogShouldSelectDifferentDefaultsForAnalysisAndImplementation() {
        List<SkillPackage> packages = new FileSystemSkillCatalog(
                Paths.get("src/main/resources/harness/skills"), SkillTrustSource.PLATFORM).discover();
        SkillSelectionPolicy policy = new SkillSelectionPolicy();

        SkillSelection analysis = policy.select(request(HarnessStage.ANALYSIS), packages);
        SkillSelection implementation = policy.select(request(HarnessStage.IMPLEMENTATION), packages);

        assertEquals(Collections.singletonList("domain-modeling-audit"), analysis.selectedSkillIds());
        assertEquals(Collections.singletonList("java-tdd"), implementation.selectedSkillIds());
    }

    private CapabilitySelectionRequest request(HarnessStage stage) {
        return new CapabilitySelectionRequest(stage, AgentRuntime.CODEX,
                StageCapabilityPolicy.defaultsFor(stage), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(), CapabilityGrant.none());
    }

    private Path writeSkill(Path root, String id, String resource) throws IOException {
        Path skillDir = Files.createDirectories(root.resolve(id).resolve("1.0.0"));
        Files.createDirectories(skillDir.resolve("references"));
        Files.write(skillDir.resolve("manifest.yml"), manifest(id, resource).getBytes(StandardCharsets.UTF_8));
        Files.write(skillDir.resolve("SKILL.md"), ("# " + displayName(id)).getBytes(StandardCharsets.UTF_8));
        if (!resource.startsWith("..")) {
            Path declared = skillDir.resolve(resource);
            Files.createDirectories(declared.getParent());
            Files.write(declared, "rules".getBytes(StandardCharsets.UTF_8));
        }
        return skillDir;
    }

    private String manifest(String id, String resource) {
        return "schemaVersion: '1'\n"
                + "id: " + id + "\n"
                + "version: 1.0.0\n"
                + "description: " + id + " description\n"
                + "stages: [ANALYSIS]\n"
                + "techTags: [java]\n"
                + "explicitTriggers: [review]\n"
                + "entry: SKILL.md\n"
                + "resources: [" + resource + "]\n"
                + "dependencies: []\n"
                + "conflicts: []\n"
                + "runtimes: [CODEX]\n"
                + "trustSource: PLATFORM\n"
                + "capabilities:\n"
                + "  - kind: COMMAND\n"
                + "    access: EXECUTE\n"
                + "    resource: mvn-test\n";
    }

    private String displayName(String id) {
        if ("java-review".equals(id)) {
            return "Java review";
        }
        return id;
    }
}
