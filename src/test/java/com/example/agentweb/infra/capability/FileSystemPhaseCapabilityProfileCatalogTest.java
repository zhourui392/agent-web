package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench 四阶段 Profile Catalog 的 schema、身份、热读取和路径安全测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class FileSystemPhaseCapabilityProfileCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void bundledCatalogShouldProvideExactlyFourVersionedProfiles() {
        FileSystemPhaseCapabilityProfileCatalog catalog =
                new FileSystemPhaseCapabilityProfileCatalog(
                        Paths.get("src/main/resources/workbench/profiles"));

        Map<WorkbenchPhase, PhaseCapabilityProfile> profiles =
                new EnumMap<WorkbenchPhase, PhaseCapabilityProfile>(
                        WorkbenchPhase.class);
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            profiles.put(phase, catalog.requireProfile(phase));
        }

        assertEquals(4, profiles.size());
        for (Map.Entry<WorkbenchPhase, PhaseCapabilityProfile> entry
                : profiles.entrySet()) {
            String phaseKey = phaseKey(entry.getKey());
            PhaseCapabilityProfile profile = entry.getValue();
            assertEquals("workbench-" + phaseKey, profile.getProfileId());
            assertEquals("1.0.0", profile.getProfileVersion());
            assertTrue(profile.getProfileHash().matches("[a-f0-9]{64}"));
            assertFalse(profile.getCapabilities().isEmpty());
            assertTrue(profile.getCapabilities().stream()
                    .anyMatch(PhaseCapabilityReference::isRequired));
            assertFalse(profile.getCapabilities().stream()
                    .anyMatch(reference -> isAbsolute(reference.getId())));
        }
    }

    @Test
    void catalogShouldHotReadSemanticProfileChangesWithoutRestart() throws Exception {
        writeCompleteCatalog(tempDir);
        FileSystemPhaseCapabilityProfileCatalog catalog =
                new FileSystemPhaseCapabilityProfileCatalog(tempDir);
        PhaseCapabilityProfile first = catalog.requireProfile(
                WorkbenchPhase.REQUIREMENT_ANALYSIS);

        writeProfile(tempDir, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "1.0.0", validReferences()
                        + "  - id: requirement-extra\n"
                        + "    required: false\n", "");
        PhaseCapabilityProfile second = catalog.requireProfile(
                WorkbenchPhase.REQUIREMENT_ANALYSIS);

        assertNotEquals(first.getProfileHash(), second.getProfileHash());
        assertEquals(4, second.getCapabilities().size());
    }

    @Test
    void catalogShouldFailClosedWhenAnyFixedPhaseIsMissingOrDuplicated()
            throws Exception {
        writeCompleteCatalog(tempDir);
        Files.delete(profileManifest(
                tempDir, WorkbenchPhase.SOLUTION_DESIGN, "1.0.0"));

        CapabilityCatalogException missing = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemPhaseCapabilityProfileCatalog(tempDir)
                        .requireProfile(WorkbenchPhase.REQUIREMENT_ANALYSIS));
        assertEquals("PHASE_PROFILE_SET_INCOMPLETE", missing.getCode());

        writeProfile(tempDir, WorkbenchPhase.SOLUTION_DESIGN,
                "1.0.0", validReferences(), "");
        writeProfile(tempDir, WorkbenchPhase.SOLUTION_DESIGN,
                "2.0.0", validReferences(), "");
        CapabilityCatalogException duplicate = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemPhaseCapabilityProfileCatalog(tempDir)
                        .requireProfile(WorkbenchPhase.SOLUTION_DESIGN));
        assertEquals("PHASE_PROFILE_VERSION_CONFLICT", duplicate.getCode());
    }

    @Test
    void catalogShouldRejectUnsupportedSchemaAndDirectoryIdentityMismatch()
            throws Exception {
        writeCompleteCatalog(tempDir);
        Path manifest = profileManifest(
                tempDir, WorkbenchPhase.REVIEW_REFACTOR, "1.0.0");
        replace(manifest, "workbench-phase-profile@1",
                "workbench-phase-profile@2");

        CapabilityCatalogException schema = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemPhaseCapabilityProfileCatalog(tempDir)
                        .requireProfile(WorkbenchPhase.REVIEW_REFACTOR));
        assertEquals("CATALOG_SCHEMA_UNSUPPORTED", schema.getCode());

        writeProfile(tempDir, WorkbenchPhase.REVIEW_REFACTOR,
                "1.0.0", validReferences(), "");
        replace(manifest, "phase: REVIEW_REFACTOR",
                "phase: IMPLEMENT_TEST");
        CapabilityCatalogException identity = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemPhaseCapabilityProfileCatalog(tempDir)
                        .requireProfile(WorkbenchPhase.REVIEW_REFACTOR));
        assertEquals("CATALOG_MANIFEST_INVALID", identity.getCode());
    }

    @Test
    void catalogShouldRejectUnknownSecretPathAndAbsoluteCapabilityId()
            throws Exception {
        writeCompleteCatalog(tempDir);
        Path manifest = profileManifest(
                tempDir, WorkbenchPhase.IMPLEMENT_TEST, "1.0.0");
        replace(manifest, "phase: IMPLEMENT_TEST",
                "phase: IMPLEMENT_TEST\nsecret: do-not-store");

        CapabilityCatalogException secret = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemPhaseCapabilityProfileCatalog(tempDir)
                        .requireProfile(WorkbenchPhase.IMPLEMENT_TEST));
        assertEquals("CATALOG_MANIFEST_INVALID", secret.getCode());

        writeProfile(tempDir, WorkbenchPhase.IMPLEMENT_TEST,
                "1.0.0", validReferences(), "");
        replace(manifest, "id: phase-rule", "id: /tmp/untrusted-skill");
        CapabilityCatalogException absolute = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemPhaseCapabilityProfileCatalog(tempDir)
                        .requireProfile(WorkbenchPhase.IMPLEMENT_TEST));
        assertEquals("CATALOG_MANIFEST_INVALID", absolute.getCode());

        writeProfile(tempDir, WorkbenchPhase.IMPLEMENT_TEST,
                "1.0.0", validReferences(), "");
        replaceFirst(manifest, "required: true",
                "required: true\n    path: /tmp/secret");
        CapabilityCatalogException path = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemPhaseCapabilityProfileCatalog(tempDir)
                        .requireProfile(WorkbenchPhase.IMPLEMENT_TEST));
        assertEquals("CATALOG_MANIFEST_INVALID", path.getCode());
    }

    @Test
    void catalogShouldWrapDuplicateAndMalformedRequiredFactsAsManifestError()
            throws Exception {
        writeCompleteCatalog(tempDir);
        Path manifest = profileManifest(
                tempDir, WorkbenchPhase.REQUIREMENT_ANALYSIS, "1.0.0");
        replace(manifest, "id: optional-skill", "id: phase-rule");

        CapabilityCatalogException duplicate = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemPhaseCapabilityProfileCatalog(tempDir)
                        .requireProfile(WorkbenchPhase.REQUIREMENT_ANALYSIS));
        assertEquals("CATALOG_MANIFEST_INVALID", duplicate.getCode());

        writeProfile(tempDir, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "1.0.0", validReferences(), "");
        replaceFirst(manifest, "required: false", "required: sometimes");
        CapabilityCatalogException required = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemPhaseCapabilityProfileCatalog(tempDir)
                        .requireProfile(WorkbenchPhase.REQUIREMENT_ANALYSIS));
        assertEquals("CATALOG_MANIFEST_INVALID", required.getCode());
    }

    @Test
    void catalogShouldRejectManifestSymlinkEscapingTrustedRoot() throws Exception {
        Path trustedRoot = Files.createDirectories(tempDir.resolve("trusted"));
        writeCompleteCatalog(trustedRoot);
        Path outside = Files.createDirectories(tempDir.resolve("outside-profile"));
        Path outsideManifest = outside.resolve("manifest.yml");
        Files.write(outsideManifest, manifest(
                WorkbenchPhase.REVIEW_REFACTOR, "2.0.0",
                validReferences(), "").getBytes(StandardCharsets.UTF_8));
        Path linkDirectory = Files.createDirectories(trustedRoot.resolve(
                phaseKey(WorkbenchPhase.REVIEW_REFACTOR)).resolve("2.0.0"));
        Files.createSymbolicLink(
                linkDirectory.resolve("manifest.yml"), outsideManifest);

        CapabilityCatalogException failure = assertThrows(
                CapabilityCatalogException.class,
                () -> new FileSystemPhaseCapabilityProfileCatalog(trustedRoot)
                        .requireProfile(WorkbenchPhase.REVIEW_REFACTOR));

        assertEquals("CATALOG_PATH_ESCAPE", failure.getCode());
    }

    private static void writeCompleteCatalog(Path root) throws Exception {
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            writeProfile(root, phase, "1.0.0", validReferences(), "");
        }
    }

    private static void writeProfile(
            Path root, WorkbenchPhase phase, String version,
            String references, String additionalRootField) throws Exception {
        Path manifest = profileManifest(root, phase, version);
        Files.createDirectories(manifest.getParent());
        Files.write(manifest, manifest(
                phase, version, references, additionalRootField)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String manifest(
            WorkbenchPhase phase, String version,
            String references, String additionalRootField) {
        return "schema: workbench-phase-profile@1\n"
                + "id: workbench-" + phaseKey(phase) + "\n"
                + "version: " + version + "\n"
                + "phase: " + phase.name() + "\n"
                + additionalRootField
                + references;
    }

    private static String validReferences() {
        return "rules:\n"
                + "  - id: phase-rule\n"
                + "    required: true\n"
                + "skills:\n"
                + "  - id: optional-skill\n"
                + "    required: false\n"
                + "mcp_servers:\n"
                + "  - id: repository-query\n"
                + "    required: false\n";
    }

    private static Path profileManifest(
            Path root, WorkbenchPhase phase, String version) {
        return root.resolve(phaseKey(phase)).resolve(version).resolve("manifest.yml");
    }

    private static String phaseKey(WorkbenchPhase phase) {
        return phase.name().toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-');
    }

    private static boolean isAbsolute(String value) {
        return Paths.get(value).isAbsolute()
                || value.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static void replace(Path path, String target, String replacement)
            throws Exception {
        String source = new String(
                Files.readAllBytes(path), StandardCharsets.UTF_8);
        Files.write(path, source.replace(target, replacement)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void replaceFirst(
            Path path, String target, String replacement)
            throws Exception {
        String source = new String(
                Files.readAllBytes(path), StandardCharsets.UTF_8);
        int offset = source.indexOf(target);
        if (offset < 0) {
            throw new AssertionError("test fixture target is missing");
        }
        String changed = source.substring(0, offset) + replacement
                + source.substring(offset + target.length());
        Files.write(path, changed.getBytes(StandardCharsets.UTF_8));
    }
}
