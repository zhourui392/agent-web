package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.CapabilityKind;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件系统 Skill Catalog Manifest、Package Hash、热发现和路径安全测试。
 *
 * @author alex
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
        assertEquals(Collections.singleton("ANALYSIS"),
                first.getManifest().getApplicableUseCases());
        assertEquals(CapabilityKind.COMMAND,
                first.getManifest().getCapabilityRequests().get(0).getKind());
        assertEquals("# Java review", first.getEntryContent());
        assertEquals("rules", new String(first.getResourceContents()
                .get("references/rules.md"), StandardCharsets.UTF_8));
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
    void shouldParsePublicApplicableUseCasesForWorkbenchPhase() throws IOException {
        Path skillDir = writeSkill(
                tempDir, "solution-design", "references/rules.md");
        replaceManifestDeclaration(
                skillDir, "applicableUseCases: [ANALYSIS]\n",
                "applicableUseCases: [SOLUTION_DESIGN]\n");

        SkillPackage skill = new FileSystemSkillCatalog(
                tempDir, SkillTrustSource.PLATFORM).discover().get(0);

        assertEquals(Collections.singleton("SOLUTION_DESIGN"),
                skill.getManifest().getApplicableUseCases());
        assertTrue(skill.getManifest().supports("SOLUTION_DESIGN", "CODEX"));
    }

    @Test
    void shouldRejectLegacyStagesManifest()
            throws IOException {
        Path skillDir = writeSkill(
                tempDir, "legacy-stages", "references/rules.md");
        replaceManifestDeclaration(
                skillDir, "applicableUseCases: [ANALYSIS]\n",
                "stages: [ANALYSIS]\n");

        CapabilityCatalogException failure = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemSkillCatalog(
                        tempDir, SkillTrustSource.PLATFORM).discover());

        assertEquals("CATALOG_MANIFEST_INVALID", failure.getCode());
    }

    @Test
    void shouldRejectManifestMissingApplicableUseCases()
            throws IOException {
        Path skillDir = writeSkill(
                tempDir, "missing-use-cases", "references/rules.md");
        replaceManifestDeclaration(
                skillDir, "applicableUseCases: [ANALYSIS]\n", "");

        CapabilityCatalogException failure = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemSkillCatalog(
                        tempDir, SkillTrustSource.PLATFORM).discover());

        assertEquals("CATALOG_MANIFEST_INVALID", failure.getCode());
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

        CapabilityCatalogException pathError = assertThrows(CapabilityCatalogException.class,
                () -> new FileSystemSkillCatalog(tempDir, SkillTrustSource.PLATFORM).discover());
        assertEquals("CATALOG_PATH_ESCAPE", pathError.getCode());

        Path trustedRoot = Files.createDirectory(tempDir.resolve("trusted"));
        writeSkill(trustedRoot, "forged", "references/rules.md");
        Path manifest = trustedRoot.resolve("forged/1.0.0/manifest.yml");
        String content = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)
                .replace("trustSource: PLATFORM", "trustSource: WORKSPACE");
        Files.write(manifest, content.getBytes(StandardCharsets.UTF_8));

        CapabilityCatalogException trustError = assertThrows(CapabilityCatalogException.class,
                () -> new FileSystemSkillCatalog(trustedRoot, SkillTrustSource.PLATFORM).discover());
        assertEquals("CATALOG_TRUST_SOURCE_MISMATCH", trustError.getCode());
    }

    @Test
    void builtInSkillsShouldCoverEveryWorkbenchProfileUseCaseThatReferencesThem() {
        List<SkillPackage> packages = new FileSystemSkillCatalog(
                Paths.get("src/main/resources/capability/skills"),
                SkillTrustSource.PLATFORM).discover();
        FileSystemPhaseCapabilityProfileCatalog profiles =
                new FileSystemPhaseCapabilityProfileCatalog(
                        Paths.get("src/main/resources/workbench/profiles"));
        int matchedReferences = 0;

        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            PhaseCapabilityProfile profile = profiles.requireProfile(phase);
            for (PhaseCapabilityReference reference : profile.getCapabilities()) {
                if (reference.getType() != PhaseCapabilityType.SKILL) {
                    continue;
                }
                SkillPackage bundled = findSkill(packages, reference.getId());
                if (bundled == null) {
                    continue;
                }
                matchedReferences++;
                assertTrue(bundled.getManifest().getApplicableUseCases()
                                .contains(phase.name()),
                        reference.getId() + " must support " + phase.name());
            }
        }
        assertEquals(6, matchedReferences);
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

    private void replaceManifestDeclaration(
            Path skillDir, String declaration, String replacement)
            throws IOException {
        Path manifestPath = skillDir.resolve("manifest.yml");
        String manifest = new String(
                Files.readAllBytes(manifestPath), StandardCharsets.UTF_8);
        Files.write(manifestPath,
                manifest.replace(declaration, replacement)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private SkillPackage findSkill(List<SkillPackage> packages, String id) {
        for (SkillPackage skillPackage : packages) {
            if (id.equals(skillPackage.getManifest().getId())) {
                return skillPackage;
            }
        }
        return null;
    }

    private String manifest(String id, String resource) {
        return "schemaVersion: '1'\n"
                + "id: " + id + "\n"
                + "version: 1.0.0\n"
                + "description: " + id + " description\n"
                + "applicableUseCases: [ANALYSIS]\n"
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
