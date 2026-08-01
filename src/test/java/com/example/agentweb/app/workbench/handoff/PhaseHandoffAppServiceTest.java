package com.example.agentweb.app.workbench.handoff;

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
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Handoff Application 的 Owner 校验、聚合调用和 Repository 编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseHandoffAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T15:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");
    private static final OwnerReference OTHER = OwnerReference.of("user-2", "Other");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");

    private WorkbenchRepository workbenchRepository;
    private PhaseHandoffRepository handoffRepository;
    private PhaseHandoffRevisionRepository revisionRepository;
    private HandoffReceptionRepository receptionRepository;
    private HandoffRunReferenceResolver runReferenceResolver;
    private PhaseHandoffAppService service;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        handoffRepository = mock(PhaseHandoffRepository.class);
        revisionRepository = mock(PhaseHandoffRevisionRepository.class);
        receptionRepository = mock(HandoffReceptionRepository.class);
        runReferenceResolver = mock(HandoffRunReferenceResolver.class);
        service = new PhaseHandoffAppService(
                workbenchRepository, handoffRepository, revisionRepository,
                receptionRepository, runReferenceResolver,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createShouldLoadOwnerScopedWorkbenchResolveRunsAndDelegateDomainFactory() {
        Workbench workbench = workbench();
        PhaseHandoffContentCommand content = content(
                Collections.singletonList("run-requirement"), "需求范围已确认");
        WorkbenchRunReference run = run(
                "run-requirement", WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS);
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(runReferenceResolver.requireReferences(
                content.getReferencedRunIds())).thenReturn(Collections.singletonList(run));

        PhaseHandoff created = service.create(OWNER, new CreatePhaseHandoffCommand(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS, content));

        assertEquals(WORKBENCH_ID, created.getWorkbenchId());
        assertEquals(WorkbenchPhase.REQUIREMENT_ANALYSIS, created.getSourcePhase());
        assertEquals("需求范围已确认", created.getSummary());
        assertEquals(0L, created.getVersion());
        assertEquals(OWNER, created.getUpdatedBy());
        assertEquals(NOW, created.getUpdatedAt());
        assertEquals("agent-web", created.getPinnedFiles().get(0).getRepositoryKey());
        assertEquals("run-requirement", created.getReferencedRuns().get(0).getRunId());
        ArgumentCaptor<PhaseHandoff> saved = ArgumentCaptor.forClass(PhaseHandoff.class);
        verify(handoffRepository).add(saved.capture());
        assertEquals(created, saved.getValue());
        ArgumentCaptor<PhaseHandoffRevision> appended =
                ArgumentCaptor.forClass(PhaseHandoffRevision.class);
        verify(revisionRepository).append(appended.capture());
        assertEquals(created.getVersion(), appended.getValue().getVersion());
        assertEquals(created.getContentHash(), appended.getValue().getContentHash());
        assertEquals(created.getSummary(), appended.getValue().getSummary());
        InOrder order = inOrder(
                workbenchRepository, runReferenceResolver,
                handoffRepository, revisionRepository);
        order.verify(workbenchRepository).findById(WORKBENCH_ID);
        order.verify(runReferenceResolver).requireReferences(content.getReferencedRunIds());
        order.verify(handoffRepository).add(created);
        order.verify(revisionRepository).append(any(PhaseHandoffRevision.class));
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void createShouldObscureNonOwnerBeforeResolvingReferencesOrPersisting() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.create(OTHER, new CreatePhaseHandoffCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        content(Collections.singletonList("run-1"), "summary"))));

        assertEquals(HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND,
                failure.getCode());
        verifyNoInteractions(
                runReferenceResolver, handoffRepository,
                revisionRepository, receptionRepository);
    }

    @Test
    void createShouldDelegatePinnedFileScopeToDomain() {
        Workbench workbench = workbench();
        PhaseHandoffContentCommand outOfScope = new PhaseHandoffContentCommand(
                "summary", Collections.<Decision>emptyList(),
                Collections.<OpenQuestion>emptyList(),
                Collections.singletonList(
                        DocumentReference.of("unselected", "README.md")),
                Collections.<String>emptyList());
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(runReferenceResolver.requireReferences(outOfScope.getReferencedRunIds()))
                .thenReturn(Collections.<WorkbenchRunReference>emptyList());

        assertThrows(IllegalArgumentException.class,
                () -> service.create(OWNER, new CreatePhaseHandoffCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS, outOfScope)));

        verify(handoffRepository, never()).add(any(PhaseHandoff.class));
        verify(revisionRepository, never()).append(any(PhaseHandoffRevision.class));
    }

    @Test
    void createShouldDelegateReferencedRunWorkbenchOwnershipToDomain() {
        Workbench workbench = workbench();
        PhaseHandoffContentCommand content = content(
                Collections.singletonList("run-foreign"), "summary");
        WorkbenchRunReference foreign = run(
                "run-foreign", WorkbenchId.of("workbench-2"),
                WorkbenchPhase.REQUIREMENT_ANALYSIS);
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(runReferenceResolver.requireReferences(content.getReferencedRunIds()))
                .thenReturn(Collections.singletonList(foreign));

        assertThrows(IllegalArgumentException.class,
                () -> service.create(OWNER, new CreatePhaseHandoffCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS, content)));

        verify(handoffRepository, never()).add(any(PhaseHandoff.class));
        verify(revisionRepository, never()).append(any(PhaseHandoffRevision.class));
    }

    @Test
    void createShouldDelegateDuplicateResolvedRunsToDomain() {
        Workbench workbench = workbench();
        PhaseHandoffContentCommand content = content(
                Arrays.asList("run-1", "run-1"), "summary");
        WorkbenchRunReference first = run(
                "run-1", WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS);
        WorkbenchRunReference duplicate = run(
                "run-1", WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS);
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(runReferenceResolver.requireReferences(content.getReferencedRunIds()))
                .thenReturn(Arrays.asList(first, duplicate));

        assertThrows(IllegalArgumentException.class,
                () -> service.create(OWNER, new CreatePhaseHandoffCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS, content)));

        verify(handoffRepository, never()).add(any(PhaseHandoff.class));
        verify(revisionRepository, never()).append(any(PhaseHandoffRevision.class));
    }

    @Test
    void createShouldDelegateArchivedWorkbenchGuardBeforeResolvingOrPersisting() {
        Workbench archived = workbench();
        archived.archive(OWNER, NOW.minusSeconds(5));
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(archived));

        WorkbenchDomainException failure = assertThrows(WorkbenchDomainException.class,
                () -> service.create(OWNER, new CreatePhaseHandoffCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        content(Collections.singletonList("run-1"), "summary"))));

        assertEquals(WorkbenchErrorCode.ARCHIVED, failure.getCode());
        verifyNoInteractions(
                runReferenceResolver, handoffRepository,
                revisionRepository, receptionRepository);
    }

    @Test
    void reviseShouldPassExpectedVersionToAggregateThenUpdateRepository() {
        Workbench workbench = workbench();
        PhaseHandoff existing = handoff(workbench, "initial", Collections.<WorkbenchRunReference>emptyList());
        String initialHash = existing.getContentHash();
        PhaseHandoffContentCommand content = content(
                Collections.singletonList("run-design"), "设计方案已确认");
        WorkbenchRunReference run = run(
                "run-design", WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN);
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(existing));
        when(runReferenceResolver.requireReferences(content.getReferencedRunIds()))
                .thenReturn(Collections.singletonList(run));

        PhaseHandoff revised = service.revise(OWNER, new RevisePhaseHandoffCommand(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS, 0L, content));

        assertEquals(existing, revised);
        assertEquals(1L, revised.getVersion());
        assertEquals("设计方案已确认", revised.getSummary());
        assertNotEquals(initialHash, revised.getContentHash());
        verify(handoffRepository).update(existing);
        ArgumentCaptor<PhaseHandoffRevision> appended =
                ArgumentCaptor.forClass(PhaseHandoffRevision.class);
        verify(revisionRepository).append(appended.capture());
        assertEquals(revised.getVersion(), appended.getValue().getVersion());
        assertEquals(revised.getContentHash(), appended.getValue().getContentHash());
        InOrder persistenceOrder = inOrder(handoffRepository, revisionRepository);
        persistenceOrder.verify(handoffRepository).update(existing);
        persistenceOrder.verify(revisionRepository)
                .append(any(PhaseHandoffRevision.class));
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void reviseVersionConflictShouldNotPersistPartially() {
        Workbench workbench = workbench();
        PhaseHandoff existing = handoff(workbench, "initial", Collections.<WorkbenchRunReference>emptyList());
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(existing));
        when(runReferenceResolver.requireReferences(Collections.<String>emptyList()))
                .thenReturn(Collections.<WorkbenchRunReference>emptyList());

        WorkbenchDomainException failure = assertThrows(WorkbenchDomainException.class,
                () -> service.revise(OWNER, new RevisePhaseHandoffCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS, 9L,
                        content(Collections.<String>emptyList(), "stale"))));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, failure.getCode());
        assertEquals("initial", existing.getSummary());
        assertEquals(0L, existing.getVersion());
        verify(handoffRepository, never()).update(any(PhaseHandoff.class));
        verify(revisionRepository, never()).append(any(PhaseHandoffRevision.class));
    }

    @Test
    void reviseMissingHandoffShouldUseApplicationNotFoundContractBeforeResolvingRuns() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.empty());

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.revise(OWNER, new RevisePhaseHandoffCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS, 0L,
                        content(Collections.singletonList("run-1"), "summary"))));

        assertEquals(HandoffApplicationErrorCode.HANDOFF_NOT_FOUND, failure.getCode());
        verifyNoInteractions(runReferenceResolver, receptionRepository);
        verify(handoffRepository, never()).update(any(PhaseHandoff.class));
        verify(revisionRepository, never()).append(any(PhaseHandoffRevision.class));
    }

    @Test
    void reviseShouldObscureNonOwnerBeforeLoadingHandoffOrResolvingRuns() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.revise(OTHER, new RevisePhaseHandoffCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS, 0L,
                        content(Collections.singletonList("run-1"), "summary"))));

        assertEquals(HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND,
                failure.getCode());
        verifyNoInteractions(
                runReferenceResolver, handoffRepository,
                revisionRepository, receptionRepository);
    }

    @Test
    void acceptShouldBindExactLatestSourceVersionAndHashThenSaveReception() {
        Workbench workbench = workbench();
        PhaseHandoff source = handoff(
                workbench, "requirements", Collections.<WorkbenchRunReference>emptyList());
        AcceptHandoffReceptionCommand command = new AcceptHandoffReceptionCommand(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                source.getVersion(), source.getContentHash());
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(source));

        HandoffReception reception = service.accept(OWNER, command);

        assertEquals(WORKBENCH_ID, reception.getWorkbenchId());
        assertEquals(WorkbenchPhase.SOLUTION_DESIGN, reception.getTargetPhase());
        assertEquals(WorkbenchPhase.REQUIREMENT_ANALYSIS, reception.getSourcePhase());
        assertEquals(source.getVersion(), reception.getSourceVersion());
        assertEquals(source.getContentHash(), reception.getSourceHash());
        assertEquals(OWNER, reception.getAcceptedBy());
        assertEquals(NOW, reception.getAcceptedAt());
        verify(receptionRepository).save(reception);
        verify(handoffRepository, never()).update(any(PhaseHandoff.class));
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void acceptShouldUseDomainStaleSemanticsAndRejectChangedSourceWithoutSave() {
        Workbench workbench = workbench();
        PhaseHandoff source = handoff(
                workbench, "latest", Collections.<WorkbenchRunReference>emptyList());
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(source));

        WorkbenchDomainException failure = assertThrows(WorkbenchDomainException.class,
                () -> service.accept(OWNER, new AcceptHandoffReceptionCommand(
                        WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        source.getVersion() + 1L, repeat('e'))));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, failure.getCode());
        verify(receptionRepository, never()).save(any(HandoffReception.class));
    }

    @Test
    void acceptedRevisionShouldResolveTheReceptionExactVersionAndHash() {
        Workbench workbench = workbench();
        PhaseHandoff acceptedHandoff = handoff(
                workbench, "accepted requirements",
                Collections.<WorkbenchRunReference>emptyList());
        PhaseHandoffRevision acceptedRevision =
                PhaseHandoffRevision.capture(acceptedHandoff);
        HandoffReception reception = HandoffReception.accept(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                acceptedRevision.getVersion(), acceptedRevision.getContentHash(),
                OWNER, NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(receptionRepository.find(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(reception));
        when(revisionRepository.findExact(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                reception.getSourceVersion(), reception.getSourceHash()))
                .thenReturn(Optional.of(acceptedRevision));

        PhaseHandoffRevision resolved = service.requireAcceptedRevision(
                OWNER, WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS);

        assertSame(acceptedRevision, resolved);
        verify(revisionRepository).findExact(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                reception.getSourceVersion(), reception.getSourceHash());
        verify(handoffRepository, never()).find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS);
    }

    @Test
    void legacyReceptionWithoutRecoverableRevisionShouldFailClosedWithoutLatestFallback() {
        Workbench workbench = workbench();
        PhaseHandoff latest = handoff(
                workbench, "legacy accepted requirements",
                Collections.<WorkbenchRunReference>emptyList());
        long legacyVersion = latest.getVersion();
        String legacyHash = latest.getContentHash();
        latest.update(
                legacyVersion, "latest requirements",
                latest.getDecisions(), latest.getOpenQuestions(),
                latest.getPinnedFiles(), latest.getReferencedRuns(),
                workbench.getRepositoryScope(), OWNER, NOW);
        HandoffReception legacyReception = HandoffReception.accept(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, legacyVersion, legacyHash,
                OWNER, NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(receptionRepository.find(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(legacyReception));
        when(revisionRepository.findExact(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                legacyVersion, legacyHash)).thenReturn(Optional.empty());
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(latest));

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.requireAcceptedRevision(
                        OWNER, WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS));

        assertEquals(HandoffApplicationErrorCode.HANDOFF_NOT_FOUND,
                failure.getCode());
        verify(revisionRepository).findExact(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                legacyVersion, legacyHash);
        verify(handoffRepository, never()).find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS);
        verify(receptionRepository, never()).save(any(HandoffReception.class));
    }

    @Test
    void acceptShouldDelegateDefaultUpstreamRuleToHandoffReceptionDomain() {
        Workbench workbench = workbench();
        PhaseHandoff source = handoff(
                workbench, "requirements", Collections.<WorkbenchRunReference>emptyList());
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(source));

        assertThrows(IllegalArgumentException.class,
                () -> service.accept(OWNER, new AcceptHandoffReceptionCommand(
                        WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        source.getVersion(), source.getContentHash())));

        verify(receptionRepository, never()).save(any(HandoffReception.class));
    }

    @Test
    void acceptShouldObscureNonOwnerBeforeLoadingSourceOrSavingReception() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.accept(OTHER, new AcceptHandoffReceptionCommand(
                        WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, 0L, repeat('a'))));

        assertEquals(HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND,
                failure.getCode());
        verifyNoInteractions(handoffRepository, receptionRepository, runReferenceResolver);
    }

    @Test
    void missingWorkbenchOrSourceHandoffShouldUseApplicationNotFoundContract() {
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.empty());

        HandoffApplicationException missingWorkbench = assertThrows(
                HandoffApplicationException.class,
                () -> service.create(OWNER, new CreatePhaseHandoffCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        content(Collections.<String>emptyList(), "summary"))));

        assertEquals(HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND,
                missingWorkbench.getCode());

        Workbench workbench = workbench();
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.empty());

        HandoffApplicationException missingSource = assertThrows(
                HandoffApplicationException.class,
                () -> service.accept(OWNER, new AcceptHandoffReceptionCommand(
                        WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, 0L, repeat('a'))));

        assertEquals(HandoffApplicationErrorCode.HANDOFF_NOT_FOUND,
                missingSource.getCode());
        verify(receptionRepository, never()).save(any(HandoffReception.class));
    }

    private static PhaseHandoffContentCommand content(List<String> runIds, String summary) {
        return new PhaseHandoffContentCommand(
                summary,
                Collections.singletonList(
                        Decision.confirmed("使用显式 Repository Scope", "避免 sibling 越权")),
                Collections.singletonList(
                        OpenQuestion.of("是否需要额外回归", "owner")),
                Collections.singletonList(
                        DocumentReference.of("agent-web", "README.md")),
                runIds);
    }

    private static PhaseHandoff handoff(Workbench workbench, String summary,
                                        List<WorkbenchRunReference> runs) {
        return PhaseHandoff.create(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                summary, Collections.<Decision>emptyList(),
                Collections.<OpenQuestion>emptyList(),
                Collections.<DocumentReference>emptyList(), runs,
                workbench.getRepositoryScope(), OWNER, NOW.minusSeconds(1));
    }

    private static WorkbenchRunReference run(
            String runId, WorkbenchId workbenchId, WorkbenchPhase phase) {
        return WorkbenchRunReference.of(runId, workbenchId, phase, "safe summary");
    }

    private static Workbench workbench() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Arrays.asList("agent-web", "shared-library"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Arrays.asList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web", repeat('1'), false),
                        ResolvedRepository.fromVerifiedFacts(
                                "shared-library", "/workspace/shared-library",
                                repeat('2'), false)),
                50);
        WorkspaceSnapshotReference snapshotReference = new WorkspaceSnapshotReference(
                "snapshot-1", scopeTopologyHash(selection), repeat('3'), 2);
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "Implement handoff",
                AgentType.CODEX, "local", scope, snapshotReference, NOW.minusSeconds(10));
    }

    private static String scopeTopologyHash(RepositorySelection selection) {
        return com.example.agentweb.domain.workspace.WorkspaceTopology.of(
                "/workspace", selection).getTopologyHash();
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        Arrays.fill(values, value);
        return new String(values);
    }
}
