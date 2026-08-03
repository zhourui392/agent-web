package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileEntry;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlitePhaseCapabilityProfileCatalog 与 AdminRepository 的真实 SQLite 集成测试。
 *
 * @author alex
 * @since 2026-08-02
 */
class SqlitePhaseCapabilityProfileCatalogTest {

    @TempDir
    Path tempDir;

    private SqlitePhaseCapabilityProfileCatalog catalog;
    private SqlitePhaseCapabilityProfileAdminRepository adminRepo;

    @BeforeEach
    void setUp() throws Exception {
        JdbcTemplate jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("profile.db"));
        catalog = new SqlitePhaseCapabilityProfileCatalog(jdbc);
        adminRepo = new SqlitePhaseCapabilityProfileAdminRepository(jdbc);
    }

    @Test
    void requireProfileShouldFailWhenTableIsEmpty() {
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            assertThrows(IllegalStateException.class,
                    () -> catalog.requireProfile(phase));
        }
    }

    @Test
    void seededProfileShouldBeReadableViaCatalog() {
        seedAllPhases();
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            PhaseCapabilityProfile profile = assertDoesNotThrow(
                    () -> catalog.requireProfile(phase));
            assertEquals(phase, profile.getPhase());
            assertTrue(profile.getProfileId().startsWith("workbench-"));
            assertTrue(!profile.getCapabilities().isEmpty());
        }
    }

    @Test
    void findAllShouldReturnAllSeededPhases() {
        seedAllPhases();
        List<PhaseCapabilityProfileEntry> entries = adminRepo.findAll();
        assertEquals(WorkbenchPhase.values().length, entries.size());
    }

    @Test
    void findByPhaseShouldReturnEmptyWhenNotSeeded() {
        assertTrue(adminRepo.findByPhase(WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .isEmpty());
    }

    @Test
    void replaceShouldUpdateCapabilitiesAndIncrementVersion() {
        seedAllPhases();
        PhaseCapabilityProfileEntry entry = adminRepo
                .findByPhase(WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .orElseThrow();
        String originalVersion = entry.getProfile().getProfileVersion();

        List<PhaseCapabilityReference> newCapabilities = Arrays.asList(
                new PhaseCapabilityReference("platform/workbench-safety",
                        PhaseCapabilityType.RULE, true),
                new PhaseCapabilityReference("new-skill",
                        PhaseCapabilityType.SKILL, false));

        PhaseCapabilityProfileEntry updated = entry.updateCapabilities(
                entry.getStorageVersion(), newCapabilities, OWNER,
                NOW.plusSeconds(60));
        adminRepo.replace(updated, entry.getStorageVersion());

        PhaseCapabilityProfile profile = catalog.requireProfile(
                WorkbenchPhase.REQUIREMENT_ANALYSIS);
        assertNotEquals(originalVersion, profile.getProfileVersion());
        assertEquals(2, profile.getCapabilities().size());
    }

    @Test
    void replaceShouldFailWithStaleVersion() {
        seedAllPhases();
        PhaseCapabilityProfileEntry entry = adminRepo
                .findByPhase(WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .orElseThrow();

        List<PhaseCapabilityReference> newCapabilities = Arrays.asList(
                new PhaseCapabilityReference("platform/workbench-safety",
                        PhaseCapabilityType.RULE, true));

        PhaseCapabilityProfileEntry updated = entry.updateCapabilities(
                entry.getStorageVersion(), newCapabilities, OWNER,
                NOW.plusSeconds(60));

        assertThrows(WorkbenchDomainException.class,
                () -> adminRepo.replace(updated, entry.getStorageVersion() + 999L));
    }

    @Test
    void profileHashShouldChangeWhenCapabilitiesChange() {
        seedAllPhases();
        PhaseCapabilityProfileEntry entry = adminRepo
                .findByPhase(WorkbenchPhase.SOLUTION_DESIGN)
                .orElseThrow();
        String originalHash = entry.getProfile().getProfileHash();

        List<PhaseCapabilityReference> newCapabilities = Arrays.asList(
                new PhaseCapabilityReference("platform/workbench-safety",
                        PhaseCapabilityType.RULE, true),
                new PhaseCapabilityReference("different-skill",
                        PhaseCapabilityType.SKILL, false));

        PhaseCapabilityProfileEntry updated = entry.updateCapabilities(
                entry.getStorageVersion(), newCapabilities, OWNER,
                NOW.plusSeconds(60));

        assertNotEquals(originalHash, updated.getProfile().getProfileHash());
    }

    private void seedAllPhases() {
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            List<PhaseCapabilityReference> capabilities = Arrays.asList(
                    new PhaseCapabilityReference("platform/workbench-safety",
                            PhaseCapabilityType.RULE, true),
                    new PhaseCapabilityReference("test-skill",
                            PhaseCapabilityType.SKILL, false));
            PhaseCapabilityProfile profile = PhaseCapabilityProfile.create(
                    "workbench-" + phase.name().toLowerCase().replace('_', '-'),
                    "1", phase, capabilities);
            PhaseCapabilityProfileEntry entry =
                    PhaseCapabilityProfileEntry.restore(
                            profile, OWNER, NOW, 1L);
            adminRepo.insertIfAbsent(entry);
        }
    }
}
