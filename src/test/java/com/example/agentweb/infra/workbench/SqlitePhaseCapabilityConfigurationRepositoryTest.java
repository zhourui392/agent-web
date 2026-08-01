package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.AdditionalCapabilityRule;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Collections;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase Capability Configuration 的真实 SQLite 和乐观锁测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqlitePhaseCapabilityConfigurationRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqlitePhaseCapabilityConfigurationRepository repository;
    private Workbench workbench;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(tempDir.resolve("capability.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "capability-creation-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(workspace, "workbench-capability");
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        repository = new SqlitePhaseCapabilityConfigurationRepository(jdbc);
    }

    @Test
    void saveAndFindShouldRoundTripAllOverrideSetsAndAuditFields() {
        CapabilityOverride override = CapabilityOverride.of(
                Collections.singleton("refactor-assistant"),
                Collections.singleton("optional-linter"),
                Collections.singleton("repository-query"),
                Collections.singleton("review/style"),
                AdditionalCapabilityRule.create("只解释变更\n不要扩大范围", 100));
        PhaseCapabilityConfiguration source = PhaseCapabilityConfiguration.create(
                workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR,
                "review-profile", "3", override,
                WorkbenchPersistenceFixtures.capabilityPolicy(),
                OWNER, NOW.plusSeconds(5));

        repository.save(source);

        PhaseCapabilityConfiguration restored = repository.find(
                        workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR)
                .orElseThrow(AssertionError::new);
        assertConfiguration(source, restored);
    }

    @Test
    void explicitEmptyOptionalMcpSelectionShouldSurvivePersistenceRoundTrip() {
        CapabilityOverride override =
                CapabilityOverride.withExplicitOptionalMcpSelection(
                        Collections.<String>emptySet(),
                        Collections.<String>emptySet(),
                        Collections.<String>emptySet(),
                        Collections.<String>emptySet(),
                        null);
        PhaseCapabilityConfiguration source = PhaseCapabilityConfiguration.create(
                workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR,
                "review-profile", "3", override,
                WorkbenchPersistenceFixtures.capabilityPolicy(),
                OWNER, NOW.plusSeconds(5));

        repository.save(source);

        PhaseCapabilityConfiguration restored = repository.find(
                        workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR)
                .orElseThrow(AssertionError::new);
        assertTrue(restored.getOverride().hasExplicitOptionalMcpSelection());
        assertTrue(restored.getOverride().getSelectedOptionalMcpIds().isEmpty());
    }

    @Test
    void legacyEmptyOptionalMcpSelectionWithoutPresenceFlagShouldUseProfileDefaults() {
        repository.save(WorkbenchPersistenceFixtures.capabilityConfiguration(workbench));
        jdbc.update("UPDATE workbench_phase_capability_config SET override_json=? "
                        + "WHERE workbench_id=? AND phase=?",
                "{\"addedOptionalSkillIds\":[],\"removedOptionalSkillIds\":[],"
                        + "\"selectedOptionalMcpIds\":[],"
                        + "\"selectedOptionalRuleIds\":[]}",
                workbench.getId().getValue(),
                WorkbenchPhase.REVIEW_REFACTOR.name());

        PhaseCapabilityConfiguration restored = repository.find(
                        workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR)
                .orElseThrow(AssertionError::new);

        assertFalse(restored.getOverride().hasExplicitOptionalMcpSelection());
    }

    @Test
    void saveUpdateAndDeleteShouldUseIndependentOptimisticVersion() {
        repository.save(WorkbenchPersistenceFixtures.capabilityConfiguration(workbench));
        PhaseCapabilityConfiguration winner = repository.find(
                        workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR)
                .orElseThrow(AssertionError::new);
        PhaseCapabilityConfiguration stale = repository.find(
                        workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR)
                .orElseThrow(AssertionError::new);
        CapabilityOverride next = CapabilityOverride.of(
                Collections.<String>emptySet(),
                Collections.singleton("optional-linter"),
                Collections.<String>emptySet(),
                Collections.singleton("review/style"));
        winner.changeOverride(0L, next, WorkbenchPersistenceFixtures.capabilityPolicy(),
                OWNER, NOW.plusSeconds(10));
        stale.changeOverride(0L, CapabilityOverride.empty(),
                WorkbenchPersistenceFixtures.capabilityPolicy(),
                OWNER, NOW.plusSeconds(10));

        repository.save(winner);

        WorkbenchDomainException saveConflict = assertThrows(
                WorkbenchDomainException.class, () -> repository.save(stale));
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, saveConflict.getCode());
        WorkbenchDomainException deleteConflict = assertThrows(
                WorkbenchDomainException.class,
                () -> repository.delete(workbench.getId(),
                        WorkbenchPhase.REVIEW_REFACTOR, 0L));
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, deleteConflict.getCode());
        repository.delete(workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR, 1L);
        assertFalse(repository.find(
                workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR).isPresent());
    }

    @Test
    void malformedOverrideJsonShouldFailFast() {
        repository.save(WorkbenchPersistenceFixtures.capabilityConfiguration(workbench));
        jdbc.update("UPDATE workbench_phase_capability_config SET override_json=? "
                        + "WHERE workbench_id=? AND phase=?",
                "[]", workbench.getId().getValue(),
                WorkbenchPhase.REVIEW_REFACTOR.name());

        assertThrows(IllegalStateException.class, () -> repository.find(
                workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR));
    }

    private void assertConfiguration(PhaseCapabilityConfiguration expected,
                                     PhaseCapabilityConfiguration actual) {
        assertEquals(expected.getWorkbenchId(), actual.getWorkbenchId());
        assertEquals(expected.getPhase(), actual.getPhase());
        assertEquals(expected.getBaseProfileId(), actual.getBaseProfileId());
        assertEquals(expected.getBaseProfileVersion(), actual.getBaseProfileVersion());
        assertEquals(expected.getOverride().getAddedOptionalSkillIds(),
                actual.getOverride().getAddedOptionalSkillIds());
        assertEquals(expected.getOverride().getRemovedOptionalSkillIds(),
                actual.getOverride().getRemovedOptionalSkillIds());
        assertEquals(expected.getOverride().getSelectedOptionalMcpIds(),
                actual.getOverride().getSelectedOptionalMcpIds());
        assertEquals(expected.getOverride().hasExplicitOptionalMcpSelection(),
                actual.getOverride().hasExplicitOptionalMcpSelection());
        assertEquals(expected.getOverride().getSelectedOptionalRuleIds(),
                actual.getOverride().getSelectedOptionalRuleIds());
        assertEquals(expected.getOverride().getAdditionalRule(),
                actual.getOverride().getAdditionalRule());
        assertEquals(expected.getUpdatedBy(), actual.getUpdatedBy());
        assertEquals(expected.getUpdatedAt(), actual.getUpdatedAt());
        assertEquals(expected.getVersion(), actual.getVersion());
    }
}
