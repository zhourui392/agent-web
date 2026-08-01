package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.Decision;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HandoffReception;
import com.example.agentweb.domain.workbench.OpenQuestion;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.PhaseHandoffRevision;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER_2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase Handoff 与 Reception 的真实 SQLite、JSON 与并发测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqlitePhaseHandoffRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqlitePhaseHandoffRepository handoffRepository;
    private SqlitePhaseHandoffRevisionRepository revisionRepository;
    private SqliteHandoffReceptionRepository receptionRepository;
    private Workbench workbench;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(tempDir.resolve("handoff.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "handoff-creation-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(workspace, "workbench-handoff");
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        handoffRepository = new SqlitePhaseHandoffRepository(jdbc);
        revisionRepository = new SqlitePhaseHandoffRevisionRepository(jdbc);
        receptionRepository = new SqliteHandoffReceptionRepository(jdbc);
    }

    @Test
    void handoffShouldRoundTripEveryStructuredFieldWithoutJsonLoss() {
        PhaseHandoff source = handoff("summary <script> is inert text");

        handoffRepository.add(source);

        PhaseHandoff restored = handoffRepository.find(
                        workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .orElseThrow(AssertionError::new);
        assertHandoff(source, restored);
        assertEquals("Need owner's answer", restored.getOpenQuestions().get(0).getText());
        assertEquals("service-api/docs/api.md",
                restored.getPinnedFiles().get(1).toString());
    }

    @Test
    void handoffUpdateShouldUseOwnOptimisticVersion() {
        handoffRepository.add(handoff("v0"));
        PhaseHandoff winner = handoffRepository.find(
                        workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .orElseThrow(AssertionError::new);
        PhaseHandoff stale = handoffRepository.find(
                        workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .orElseThrow(AssertionError::new);
        winner.update(0L, "winner", winner.getDecisions(), winner.getOpenQuestions(),
                winner.getPinnedFiles(), winner.getReferencedRuns(),
                workbench.getRepositoryScope(), OWNER, NOW.plusSeconds(10));
        stale.update(0L, "stale", stale.getDecisions(), stale.getOpenQuestions(),
                stale.getPinnedFiles(), stale.getReferencedRuns(),
                workbench.getRepositoryScope(), OWNER, NOW.plusSeconds(10));

        handoffRepository.update(winner);

        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class, () -> handoffRepository.update(stale));
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, conflict.getCode());
        assertEquals("winner", handoffRepository.find(
                        workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .orElseThrow(AssertionError::new).getSummary());
    }

    @Test
    void duplicateInitialHandoffShouldUseVersionConflictWithoutOverwritingCurrent() {
        PhaseHandoff winner = handoff("winner");
        PhaseHandoff concurrent = handoff("concurrent");
        handoffRepository.add(winner);

        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class,
                () -> handoffRepository.add(concurrent));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT,
                conflict.getCode());
        PhaseHandoff current = handoffRepository.find(
                        workbench.getId(),
                        WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .orElseThrow(AssertionError::new);
        assertEquals("winner", current.getSummary());
        assertEquals(winner.getContentHash(), current.getContentHash());
    }

    @Test
    void revisionRepositoryShouldAppendImmutableExactRevisionsWithoutMutatingLatest() {
        PhaseHandoff handoff = handoff("revision zero");
        handoffRepository.add(handoff);
        revisionRepository.append(PhaseHandoffRevision.capture(handoff));
        long revisionZeroVersion = handoff.getVersion();
        String revisionZeroHash = handoff.getContentHash();

        handoff.update(
                revisionZeroVersion, "revision one",
                handoff.getDecisions(), handoff.getOpenQuestions(),
                handoff.getPinnedFiles(), handoff.getReferencedRuns(),
                workbench.getRepositoryScope(), OWNER_2, NOW.plusSeconds(10));
        handoffRepository.update(handoff);
        revisionRepository.append(PhaseHandoffRevision.capture(handoff));

        PhaseHandoffRevision revisionZero = revisionRepository.findExact(
                        workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        revisionZeroVersion, revisionZeroHash)
                .orElseThrow(AssertionError::new);
        PhaseHandoffRevision revisionOne = revisionRepository.findExact(
                        workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        handoff.getVersion(), handoff.getContentHash())
                .orElseThrow(AssertionError::new);
        assertEquals("revision zero", revisionZero.getSummary());
        assertEquals(revisionZeroVersion, revisionZero.getVersion());
        assertEquals(revisionZeroHash, revisionZero.getContentHash());
        assertEquals("revision one", revisionOne.getSummary());
        assertEquals(OWNER_2, revisionOne.getUpdatedBy());
        assertEquals(Integer.valueOf(2), jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_phase_handoff_revision "
                        + "WHERE workbench_id=? AND phase=?",
                Integer.class, workbench.getId().getValue(),
                WorkbenchPhase.REQUIREMENT_ANALYSIS.name()));
        assertFalse(revisionRepository.findExact(
                workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS,
                revisionZeroVersion, handoff.getContentHash()).isPresent());
        assertEquals("revision one", handoffRepository.find(
                        workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .orElseThrow(AssertionError::new).getSummary());

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE workbench_phase_handoff_revision SET summary=? "
                        + "WHERE workbench_id=? AND phase=? AND version=?",
                "tampered", workbench.getId().getValue(),
                WorkbenchPhase.REQUIREMENT_ANALYSIS.name(), revisionZeroVersion));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM workbench_phase_handoff_revision "
                        + "WHERE workbench_id=? AND phase=? AND version=?",
                workbench.getId().getValue(),
                WorkbenchPhase.REQUIREMENT_ANALYSIS.name(), revisionZeroVersion));
    }

    @Test
    void legacyMigrationShouldBackfillOnlyTheRecoverableCurrentRevision() throws Exception {
        PhaseHandoff handoff = handoff("legacy accepted revision");
        handoffRepository.add(handoff);
        long legacyVersion = handoff.getVersion();
        String legacyHash = handoff.getContentHash();
        receptionRepository.save(HandoffReception.accept(
                workbench.getId(), WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, legacyVersion, legacyHash,
                OWNER, NOW.plusSeconds(6)));
        handoff.update(
                legacyVersion, "legacy latest revision",
                handoff.getDecisions(), handoff.getOpenQuestions(),
                handoff.getPinnedFiles(), handoff.getReferencedRuns(),
                workbench.getRepositoryScope(), OWNER_2, NOW.plusSeconds(10));
        handoffRepository.update(handoff);
        long currentVersion = handoff.getVersion();
        String currentHash = handoff.getContentHash();

        jdbc.execute("DROP TABLE workbench_phase_handoff_revision");
        new SqliteInitializer(jdbc).init();
        revisionRepository = new SqlitePhaseHandoffRevisionRepository(jdbc);

        assertFalse(revisionRepository.findExact(
                workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS,
                legacyVersion, legacyHash).isPresent());
        PhaseHandoffRevision recoveredCurrent = revisionRepository.findExact(
                        workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        currentVersion, currentHash)
                .orElseThrow(AssertionError::new);
        assertEquals("legacy latest revision", recoveredCurrent.getSummary());
        HandoffReception preservedReception = receptionRepository.find(
                        workbench.getId(), WorkbenchPhase.SOLUTION_DESIGN,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .orElseThrow(AssertionError::new);
        assertEquals(legacyVersion, preservedReception.getSourceVersion());
        assertEquals(legacyHash, preservedReception.getSourceHash());
    }

    @Test
    void receptionShouldRoundTripAndReplaceOnlySameUpstreamPair() {
        PhaseHandoff handoff = handoff("accepted");
        handoffRepository.add(handoff);
        HandoffReception first = HandoffReception.accept(
                workbench.getId(), WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, handoff.getVersion(),
                handoff.getContentHash(), OWNER, NOW.plusSeconds(10));
        receptionRepository.save(first);

        HandoffReception restored = receptionRepository.find(
                        workbench.getId(), WorkbenchPhase.SOLUTION_DESIGN,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .orElseThrow(AssertionError::new);
        assertReception(first, restored);

        handoff.update(0L, "new version", handoff.getDecisions(),
                handoff.getOpenQuestions(), handoff.getPinnedFiles(),
                handoff.getReferencedRuns(), workbench.getRepositoryScope(),
                OWNER_2, NOW.plusSeconds(11));
        handoffRepository.update(handoff);
        HandoffReception newer = HandoffReception.accept(
                workbench.getId(), WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, handoff.getVersion(),
                handoff.getContentHash(), OWNER_2, NOW.plusSeconds(12));
        receptionRepository.save(newer);

        assertReception(newer, receptionRepository.find(
                        workbench.getId(), WorkbenchPhase.SOLUTION_DESIGN,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .orElseThrow(AssertionError::new));
    }

    @Test
    void malformedJsonMissingParentAndStoredHashMismatchShouldFailClosed() {
        PhaseHandoff source = handoff("valid");
        handoffRepository.add(source);
        jdbc.update("UPDATE workbench_phase_handoff SET decisions_json=? "
                        + "WHERE workbench_id=? AND phase=?",
                "{not-json", workbench.getId().getValue(),
                WorkbenchPhase.REQUIREMENT_ANALYSIS.name());
        assertThrows(IllegalStateException.class, () -> handoffRepository.find(
                workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS));

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO workbench_handoff_reception "
                        + "(workbench_id,target_phase,source_phase,source_version,source_hash,"
                        + "accepted_by_id,accepted_by_name,accepted_at) VALUES (?,?,?,?,?,?,?,?)",
                "missing", WorkbenchPhase.SOLUTION_DESIGN.name(),
                WorkbenchPhase.REQUIREMENT_ANALYSIS.name(), 0,
                WorkbenchPersistenceFixtures.HASH_A, "user", "User", NOW.toEpochMilli()));
        assertFalse(receptionRepository.find(
                workbench.getId(), WorkbenchPhase.IMPLEMENT_TEST,
                WorkbenchPhase.SOLUTION_DESIGN).isPresent());
    }

    private PhaseHandoff handoff(String summary) {
        return PhaseHandoff.create(
                workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS, summary,
                Arrays.asList(
                        Decision.confirmed("Use SQLite", "MVP deployment target"),
                        Decision.confirmed("Keep four phases", null)),
                Collections.singletonList(
                        OpenQuestion.of("Need owner's answer", "Owner")),
                Arrays.asList(
                        DocumentReference.of("agent-web", "docs/design.md"),
                        DocumentReference.of("service-api", "docs/api.md")),
                Collections.singletonList(WorkbenchRunReference.of(
                        "analysis-run", workbench.getId(),
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "需求分析已完成")),
                workbench.getRepositoryScope(), OWNER, NOW.plusSeconds(5));
    }

    private void assertHandoff(PhaseHandoff expected, PhaseHandoff actual) {
        assertEquals(expected.getWorkbenchId(), actual.getWorkbenchId());
        assertEquals(expected.getSourcePhase(), actual.getSourcePhase());
        assertEquals(expected.getSummary(), actual.getSummary());
        assertEquals(expected.getDecisions(), actual.getDecisions());
        assertEquals(expected.getOpenQuestions(), actual.getOpenQuestions());
        assertEquals(expected.getPinnedFiles(), actual.getPinnedFiles());
        assertEquals(expected.getReferencedRuns(), actual.getReferencedRuns());
        assertEquals(expected.getContentHash(), actual.getContentHash());
        assertEquals(expected.getUpdatedBy(), actual.getUpdatedBy());
        assertEquals(expected.getUpdatedAt(), actual.getUpdatedAt());
        assertEquals(expected.getVersion(), actual.getVersion());
    }

    private void assertReception(HandoffReception expected, HandoffReception actual) {
        assertEquals(expected.getWorkbenchId(), actual.getWorkbenchId());
        assertEquals(expected.getTargetPhase(), actual.getTargetPhase());
        assertEquals(expected.getSourcePhase(), actual.getSourcePhase());
        assertEquals(expected.getSourceVersion(), actual.getSourceVersion());
        assertEquals(expected.getSourceHash(), actual.getSourceHash());
        assertEquals(expected.getAcceptedBy(), actual.getAcceptedBy());
        assertEquals(expected.getAcceptedAt(), actual.getAcceptedAt());
    }
}
