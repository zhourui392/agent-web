package com.example.agentweb.app.workbench.review;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmationRepository;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.ReviewOpinionRepository;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Review Owner Application 的编排、Owner 隔离和安全冲突投影测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ReviewOwnerServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T15:00:00Z");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final OwnerReference OTHER =
            OwnerReference.of("owner-2", "Other");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final String CONTENT_A = "提取 Review 策略对象";
    private static final String CONTENT_B = "拆分 Review 策略并运行测试";
    private static final String HASH_A = CanonicalHashing.sha256(CONTENT_A);
    private static final String HASH_B = CanonicalHashing.sha256(CONTENT_B);

    private WorkbenchRepository workbenchRepository;
    private ReviewOpinionRepository opinionRepository;
    private ReviewModifyConfirmationRepository confirmationRepository;
    private ReviewConfirmationIdGenerator idGenerator;
    private ReviewOwnerService service;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        opinionRepository = mock(ReviewOpinionRepository.class);
        confirmationRepository =
                mock(ReviewModifyConfirmationRepository.class);
        idGenerator = mock(ReviewConfirmationIdGenerator.class);
        service = new ReviewOwnerService(
                workbenchRepository, opinionRepository,
                confirmationRepository, idGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void saveShouldCreateInitialThenReviseOnlyThroughDomainVersionRules() {
        Workbench workbench = workbench();
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(opinionRepository.findLatest(WORKBENCH_ID))
                .thenReturn(Optional.<ReviewOpinion>empty())
                .thenReturn(Optional.of(opinion(1L, HASH_A)));

        ReviewOpinionView initial = service.saveOpinion(
                OWNER, new SaveReviewOpinionCommand(
                        WORKBENCH_ID, 0L, CONTENT_A));
        ReviewOpinionView revised = service.saveOpinion(
                OWNER, new SaveReviewOpinionCommand(
                        WORKBENCH_ID, 1L, CONTENT_B));

        assertEquals(1L, initial.getVersion());
        assertEquals(HASH_A, initial.getContentHash());
        assertEquals(CONTENT_A, initial.getContent());
        assertEquals(2L, revised.getVersion());
        assertEquals(HASH_B, revised.getContentHash());
        assertFalse(initial.isReadOnly());
        ArgumentCaptor<ReviewOpinion> saved =
                ArgumentCaptor.forClass(ReviewOpinion.class);
        verify(opinionRepository,
                org.mockito.Mockito.times(2)).add(saved.capture());
        assertEquals(Arrays.asList(1L, 2L), Arrays.asList(
                saved.getAllValues().get(0).getVersion(),
                saved.getAllValues().get(1).getVersion()));
    }

    @Test
    void staleSaveShouldReturn409ErrorWithOnlySafeCurrentOpinionProjection() {
        ReviewOpinion current = opinion(2L, HASH_B);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(opinionRepository.findLatest(WORKBENCH_ID))
                .thenReturn(Optional.of(current));

        ReviewApplicationException failure = assertThrows(
                ReviewApplicationException.class,
                () -> service.saveOpinion(
                        OWNER, new SaveReviewOpinionCommand(
                                WORKBENCH_ID, 1L, CONTENT_A)));

        assertEquals(ReviewApplicationErrorCode.VERSION_CONFLICT,
                failure.getCode());
        assertEquals(2L, failure.getCurrentOpinion().getVersion());
        assertEquals(HASH_B,
                failure.getCurrentOpinion().getContentHash());
        assertFalse(Arrays.stream(failure.getCurrentOpinion().getClass()
                        .getMethods())
                .anyMatch(method -> "getReviewedBy".equals(
                        method.getName())));
        verify(opinionRepository, never()).add(any());
    }

    @Test
    void confirmShouldPersistOnlyExactCurrentOpinionProofFromHumanAction() {
        ReviewOpinion current = opinion(2L, HASH_B);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(opinionRepository.findLatest(WORKBENCH_ID))
                .thenReturn(Optional.of(current));
        when(idGenerator.nextId()).thenReturn("confirmation-2");

        ReviewConfirmationView view = service.confirmModification(
                OWNER, new ConfirmReviewModificationCommand(
                        WORKBENCH_ID, 2L, HASH_B));

        assertEquals("confirmation-2", view.getConfirmationId());
        assertEquals(2L, view.getOpinionVersion());
        assertEquals(HASH_B, view.getOpinionHash());
        assertFalse(view.isReadOnly());
        ArgumentCaptor<ReviewModifyConfirmation> saved =
                ArgumentCaptor.forClass(ReviewModifyConfirmation.class);
        verify(confirmationRepository).add(saved.capture());
        assertEquals(OWNER, saved.getValue().getConfirmedBy());
    }

    @Test
    void staleConfirmationShouldExposeCurrentOpinionAndNeverCreateIdOrRecord() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(opinionRepository.findLatest(WORKBENCH_ID))
                .thenReturn(Optional.of(opinion(2L, HASH_B)));

        ReviewApplicationException failure = assertThrows(
                ReviewApplicationException.class,
                () -> service.confirmModification(
                        OWNER, new ConfirmReviewModificationCommand(
                                WORKBENCH_ID, 2L, HASH_A)));

        assertEquals(ReviewApplicationErrorCode.VERSION_CONFLICT,
                failure.getCode());
        assertEquals(2L, failure.getCurrentOpinion().getVersion());
        assertEquals(HASH_B,
                failure.getCurrentOpinion().getContentHash());
        verifyNoInteractions(idGenerator, confirmationRepository);
    }

    @Test
    void archivedOwnerShouldRecoverOpinionAndExactLatestConfirmationReadOnly() {
        Workbench archived = workbench();
        archived.archive(OWNER, NOW.minusSeconds(1));
        ReviewOpinion current = opinion(2L, HASH_B);
        ReviewModifyConfirmation confirmation =
                ReviewModifyConfirmation.confirm(
                        "confirmation-2", current, OWNER,
                        NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(archived));
        when(opinionRepository.findLatest(WORKBENCH_ID))
                .thenReturn(Optional.of(current));
        when(confirmationRepository.findLatest(
                WORKBENCH_ID, 2L, HASH_B))
                .thenReturn(Optional.of(confirmation));

        ReviewOpinionView opinionView =
                service.getOpinion(OWNER, WORKBENCH_ID);
        ReviewConfirmationView confirmationView =
                service.getConfirmation(OWNER, WORKBENCH_ID);

        assertTrue(opinionView.isReadOnly());
        assertTrue(confirmationView.isReadOnly());
        assertEquals("confirmation-2",
                confirmationView.getConfirmationId());
        verify(confirmationRepository).findLatest(
                WORKBENCH_ID, 2L, HASH_B);
    }

    @Test
    void archivedWorkbenchShouldRejectOpinionAndConfirmationWrites() {
        Workbench archived = workbench();
        archived.archive(OWNER, NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(archived));

        WorkbenchDomainException opinionFailure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.saveOpinion(
                        OWNER, new SaveReviewOpinionCommand(
                                WORKBENCH_ID, 0L, CONTENT_A)));
        WorkbenchDomainException confirmationFailure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.confirmModification(
                        OWNER, new ConfirmReviewModificationCommand(
                                WORKBENCH_ID, 1L, HASH_A)));

        assertEquals(WorkbenchErrorCode.ARCHIVED,
                opinionFailure.getCode());
        assertEquals(WorkbenchErrorCode.ARCHIVED,
                confirmationFailure.getCode());
        verifyNoInteractions(
                opinionRepository, confirmationRepository, idGenerator);
    }

    @Test
    void foreignOwnerShouldReceive404BeforeAnyReviewRecordLookup() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));

        assertThrows(WorkbenchNotFoundException.class,
                () -> service.getOpinion(OTHER, WORKBENCH_ID));
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.getConfirmation(OTHER, WORKBENCH_ID));
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.saveOpinion(
                        OTHER, new SaveReviewOpinionCommand(
                                WORKBENCH_ID, 0L, CONTENT_A)));

        verifyNoInteractions(
                opinionRepository, confirmationRepository, idGenerator);
    }

    @Test
    void absentOpinionOrConfirmationShouldUseStableApplicationNotFoundCodes() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));
        when(opinionRepository.findLatest(WORKBENCH_ID))
                .thenReturn(Optional.<ReviewOpinion>empty())
                .thenReturn(Optional.of(opinion(1L, HASH_A)));
        when(confirmationRepository.findLatest(
                WORKBENCH_ID, 1L, HASH_A))
                .thenReturn(Optional.<ReviewModifyConfirmation>empty());

        ReviewApplicationException missingOpinion = assertThrows(
                ReviewApplicationException.class,
                () -> service.getOpinion(OWNER, WORKBENCH_ID));
        ReviewApplicationException missingConfirmation = assertThrows(
                ReviewApplicationException.class,
                () -> service.getConfirmation(OWNER, WORKBENCH_ID));

        assertEquals(ReviewApplicationErrorCode.OPINION_NOT_FOUND,
                missingOpinion.getCode());
        assertEquals(ReviewApplicationErrorCode.CONFIRMATION_NOT_FOUND,
                missingConfirmation.getCode());
    }

    private static ReviewOpinion opinion(long version, String hash) {
        String content = HASH_A.equals(hash) ? CONTENT_A : CONTENT_B;
        return ReviewOpinion.restore(
                WORKBENCH_ID, version, content, hash, OWNER,
                NOW.minusSeconds(2));
    }

    private static Workbench workbench() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('1'), false)),
                50);
        WorkspaceSnapshotReference snapshot =
                new WorkspaceSnapshotReference(
                        "snapshot-1",
                        com.example.agentweb.domain.workspace
                                .WorkspaceTopology.of(
                                "/workspace", selection)
                                .getTopologyHash(),
                        repeat('2'), 1);
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "Review code",
                AgentType.CODEX, "local", scope, snapshot, NOW.minusSeconds(10));
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        Arrays.fill(values, value);
        return new String(values);
    }
}
