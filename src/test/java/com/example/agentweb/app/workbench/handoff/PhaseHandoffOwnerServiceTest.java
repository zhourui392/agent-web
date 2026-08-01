package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.Decision;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HandoffReception;
import com.example.agentweb.domain.workbench.HandoffReceptionRepository;
import com.example.agentweb.domain.workbench.OpenQuestion;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.PhaseHandoffRepository;
import com.example.agentweb.domain.workbench.PhaseHandoffRevision;
import com.example.agentweb.domain.workbench.PhaseHandoffRevisionRepository;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Handoff Owner 门面的 upsert、归档读取、Reception 与安全 diff 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseHandoffOwnerServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T15:00:00Z");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final OwnerReference OTHER =
            OwnerReference.of("owner-2", "Other");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");

    private PhaseHandoffAppService mutationService;
    private WorkbenchRepository workbenchRepository;
    private PhaseHandoffRepository handoffRepository;
    private PhaseHandoffRevisionRepository revisionRepository;
    private HandoffReceptionRepository receptionRepository;
    private WorkbenchTelemetry telemetry;
    private PhaseHandoffOwnerService service;

    @BeforeEach
    void setUp() {
        mutationService = mock(PhaseHandoffAppService.class);
        workbenchRepository = mock(WorkbenchRepository.class);
        handoffRepository = mock(PhaseHandoffRepository.class);
        revisionRepository = mock(PhaseHandoffRevisionRepository.class);
        receptionRepository = mock(HandoffReceptionRepository.class);
        telemetry = mock(WorkbenchTelemetry.class);
        service = new PhaseHandoffOwnerService(
                mutationService, workbenchRepository, handoffRepository,
                revisionRepository, receptionRepository, telemetry);
    }

    @Test
    void saveShouldCreateOnlyWhenHandoffDoesNotExistAndVersionIsZero() {
        Workbench workbench = workbench();
        PhaseHandoffContentCommand content = content("initial");
        PhaseHandoff created = handoff(workbench, "initial", NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.<PhaseHandoff>empty());
        when(mutationService.create(any(), any())).thenReturn(created);

        PhaseHandoffProjection result = service.save(
                OWNER, WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                0L, content);

        assertEquals(0L, result.getVersion());
        assertEquals("initial", result.getSummary());
        assertFalse(result.isReadOnly());
        ArgumentCaptor<CreatePhaseHandoffCommand> command =
                ArgumentCaptor.forClass(CreatePhaseHandoffCommand.class);
        verify(mutationService).create(
                org.mockito.ArgumentMatchers.eq(OWNER), command.capture());
        assertEquals(WORKBENCH_ID, command.getValue().getWorkbenchId());
        assertEquals(WorkbenchPhase.REQUIREMENT_ANALYSIS,
                command.getValue().getSourcePhase());
        assertEquals(content, command.getValue().getContent());
        verify(mutationService, never()).revise(any(), any());
    }

    @Test
    void saveShouldReviseExistingVersionZeroInsteadOfTreatingZeroAsCreateOnly() {
        Workbench workbench = workbench();
        PhaseHandoff existing = handoff(
                workbench, "initial", NOW.minusSeconds(2));
        existing.update(
                0L, "revised", existing.getDecisions(),
                existing.getOpenQuestions(), existing.getPinnedFiles(),
                existing.getReferencedRuns(), workbench.getRepositoryScope(),
                OWNER, NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(existing));
        when(mutationService.revise(any(), any())).thenReturn(existing);

        PhaseHandoffProjection result = service.save(
                OWNER, WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                0L, content("revised"));

        assertEquals(1L, result.getVersion());
        ArgumentCaptor<RevisePhaseHandoffCommand> command =
                ArgumentCaptor.forClass(RevisePhaseHandoffCommand.class);
        verify(mutationService).revise(
                org.mockito.ArgumentMatchers.eq(OWNER), command.capture());
        assertEquals(0L, command.getValue().getExpectedVersion());
        verify(mutationService, never()).create(any(), any());
    }

    @Test
    void saveMissingNonInitialVersionShouldReturnNotFoundWithoutMutation() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.<PhaseHandoff>empty());

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.save(
                        OWNER, WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        3L, content("stale")));

        assertEquals(HandoffApplicationErrorCode.HANDOFF_NOT_FOUND,
                failure.getCode());
        verifyNoInteractions(mutationService);
    }

    @Test
    void saveVersionConflictShouldCarryFreshSafeCurrentProjection() {
        Workbench workbench = workbench();
        PhaseHandoff current = handoff(
                workbench, "remote current", NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(current));
        when(mutationService.revise(any(), any())).thenThrow(
                new WorkbenchDomainException(
                        WorkbenchErrorCode.VERSION_CONFLICT, "stale"));

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.save(
                        OWNER, WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        9L, content("local draft")));

        assertEquals(HandoffApplicationErrorCode.VERSION_CONFLICT,
                failure.getCode());
        assertEquals("remote current", failure.getCurrent().getSummary());
        assertFalse(Arrays.stream(failure.getCurrent().getReferencedRuns()
                        .get(0).getClass().getMethods())
                .anyMatch(method -> "getWorkbenchId".equals(method.getName())));
        verify(telemetry).handoffConflict();
        verify(telemetry).writeConflict();
    }

    @Test
    void concurrentInitialCreateShouldTranslateRepositoryConflictWithCurrent() {
        Workbench workbench = workbench();
        PhaseHandoff concurrentWinner = handoff(
                workbench, "concurrent winner", NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.<PhaseHandoff>empty())
                .thenReturn(Optional.of(concurrentWinner));
        when(mutationService.create(any(), any())).thenThrow(
                new WorkbenchDomainException(
                        WorkbenchErrorCode.VERSION_CONFLICT,
                        "stale or existing phase handoff"));

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.save(
                        OWNER, WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        0L, content("local initial")));

        assertEquals(HandoffApplicationErrorCode.VERSION_CONFLICT,
                failure.getCode());
        assertEquals("concurrent winner", failure.getCurrent().getSummary());
        verify(mutationService, never()).revise(any(), any());
        verify(telemetry).handoffConflict();
        verify(telemetry).writeConflict();
    }

    @Test
    void getShouldAllowArchivedOwnerAndReturnReadOnlyProjection() {
        Workbench archived = workbench();
        archived.archive(OWNER, NOW.minusSeconds(1));
        PhaseHandoff handoff = handoff(
                archived, "archived", NOW.minusSeconds(2));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(archived));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(handoff));

        PhaseHandoffProjection result = service.get(
                OWNER, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS);

        assertTrue(result.isReadOnly());
        assertEquals("run-1", result.getReferencedRuns().get(0).getRunId());
        assertEquals(WorkbenchPhase.REQUIREMENT_ANALYSIS,
                result.getReferencedRuns().get(0).getPhase());
        assertEquals("safe summary",
                result.getReferencedRuns().get(0).getSafeSummary());
    }

    @Test
    void getShouldHideForeignOwnerBeforeLoadingHandoff() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.get(
                        OTHER, WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS));

        assertEquals(HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND,
                failure.getCode());
        verifyNoInteractions(handoffRepository);
    }

    @Test
    void sourceShouldLoadExactAcceptedRevisionAndDiffAgainstLatest() {
        Workbench workbench = workbench();
        PhaseHandoff accepted = handoff(
                workbench, "accepted", NOW.minusSeconds(3));
        PhaseHandoffRevision revision = PhaseHandoffRevision.capture(accepted);
        HandoffReception reception = HandoffReception.accept(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                revision.getVersion(), revision.getContentHash(), OWNER,
                NOW.minusSeconds(2));
        PhaseHandoff latest = handoff(
                workbench, "accepted", NOW.minusSeconds(3));
        latest.update(
                0L, "latest", Collections.singletonList(
                        Decision.confirmed("new decision", null)),
                Collections.<OpenQuestion>emptyList(),
                Arrays.asList(
                        DocumentReference.of("agent-web", "README.md"),
                        DocumentReference.of("agent-web", "docs/design.md")),
                Collections.singletonList(run("run-2")),
                workbench.getRepositoryScope(), OWNER, NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(latest));
        when(receptionRepository.find(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(reception));
        when(revisionRepository.findExact(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                revision.getVersion(), revision.getContentHash()))
                .thenReturn(Optional.of(revision));

        HandoffSourcePreview result = service.source(
                OWNER, WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN);

        assertTrue(result.isStale());
        assertEquals(1L, result.getLatestSource().getVersion());
        assertEquals(0L, result.getAcceptedSource().getVersion());
        assertEquals(reception.getSourceHash(),
                result.getReception().getSourceHash());
        assertTrue(result.getDiff().isSummaryChanged());
        assertEquals(1, result.getDiff().getDecisions().getAdded());
        assertEquals(0, result.getDiff().getDecisions().getRemoved());
        assertEquals(1, result.getDiff().getPinnedFiles().getAdded());
        assertEquals(0, result.getDiff().getPinnedFiles().getRemoved());
        assertEquals(1, result.getDiff().getReferencedRuns().getAdded());
        assertEquals(1, result.getDiff().getReferencedRuns().getRemoved());
    }

    @Test
    void sourceForRequirementAnalysisShouldHaveNoDefaultUpstream() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));

        HandoffSourcePreview result = service.source(
                OWNER, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS);

        assertEquals(WorkbenchPhase.REQUIREMENT_ANALYSIS,
                result.getTargetPhase());
        assertNull(result.getLatestSource());
        assertNull(result.getReception());
        assertNull(result.getAcceptedSource());
        assertFalse(result.isStale());
        assertNull(result.getDiff());
        verifyNoInteractions(
                handoffRepository, receptionRepository, revisionRepository);
    }

    @Test
    void sourceShouldFailClosedWhenAcceptedExactRevisionIsMissing() {
        Workbench workbench = workbench();
        PhaseHandoff latest = handoff(
                workbench, "latest", NOW.minusSeconds(2));
        HandoffReception reception = HandoffReception.accept(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                latest.getVersion(), latest.getContentHash(), OWNER,
                NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(latest));
        when(receptionRepository.find(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(reception));
        when(revisionRepository.findExact(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                reception.getSourceVersion(), reception.getSourceHash()))
                .thenReturn(Optional.<PhaseHandoffRevision>empty());

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.source(
                        OWNER, WORKBENCH_ID,
                        WorkbenchPhase.SOLUTION_DESIGN));

        assertEquals(HandoffApplicationErrorCode.HANDOFF_NOT_FOUND,
                failure.getCode());
    }

    @Test
    void acceptShouldTranslateStaleSourceToDedicatedConflict() {
        AcceptHandoffReceptionCommand command =
                new AcceptHandoffReceptionCommand(
                        WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        3L, repeat('a'));
        when(mutationService.accept(OWNER, command)).thenThrow(
                new WorkbenchDomainException(
                        WorkbenchErrorCode.VERSION_CONFLICT, "changed"));

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.accept(OWNER, command));

        assertEquals(HandoffApplicationErrorCode.SOURCE_CHANGED,
                failure.getCode());
        verify(telemetry).handoffConflict();
        verify(telemetry).writeConflict();
    }

    private static PhaseHandoffContentCommand content(String summary) {
        return new PhaseHandoffContentCommand(
                summary, Collections.<Decision>emptyList(),
                Collections.<OpenQuestion>emptyList(),
                Collections.singletonList(
                        DocumentReference.of("agent-web", "README.md")),
                Collections.singletonList("run-1"));
    }

    private static PhaseHandoff handoff(
            Workbench workbench, String summary, Instant updatedAt) {
        return PhaseHandoff.create(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                summary, Collections.<Decision>emptyList(),
                Collections.<OpenQuestion>emptyList(),
                Collections.singletonList(
                        DocumentReference.of("agent-web", "README.md")),
                Collections.singletonList(run("run-1")),
                workbench.getRepositoryScope(), OWNER, updatedAt);
    }

    private static WorkbenchRunReference run(String runId) {
        return WorkbenchRunReference.of(
                runId, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "safe summary");
    }

    private static Workbench workbench() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Arrays.asList("agent-web", "shared-library"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Arrays.asList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('1'), false),
                        ResolvedRepository.fromVerifiedFacts(
                                "shared-library", "/workspace/shared-library",
                                repeat('2'), false)),
                50);
        WorkspaceSnapshotReference snapshotReference =
                new WorkspaceSnapshotReference(
                        "snapshot-1", scopeTopologyHash(selection),
                        repeat('3'), 2);
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "Implement handoff",
                AgentType.CODEX, "local", scope, snapshotReference,
                NOW.minusSeconds(10));
    }

    private static String scopeTopologyHash(
            RepositorySelection selection) {
        return com.example.agentweb.domain.workspace.WorkspaceTopology.of(
                "/workspace", selection).getTopologyHash();
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        Arrays.fill(values, value);
        return new String(values);
    }
}
