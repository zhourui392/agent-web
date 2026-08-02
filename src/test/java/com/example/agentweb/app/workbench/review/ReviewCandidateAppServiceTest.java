package com.example.agentweb.app.workbench.review;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.query.PhaseConversationMessagePage;
import com.example.agentweb.app.workbench.query.PhaseConversationMessageRequest;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.ReviewOpinionRepository;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Review Candidate 当前公开消息只读编排与 Owner 隔离测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ReviewCandidateAppServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T17:30:00Z");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final OwnerReference OTHER =
            OwnerReference.of("owner-2", "Other");

    private WorkbenchRepository workbenchRepository;
    private ReviewOpinionRepository opinionRepository;
    private WorkbenchQueryService queryService;
    private ReviewCandidateAppService service;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        opinionRepository = mock(ReviewOpinionRepository.class);
        queryService = mock(WorkbenchQueryService.class);
        service = new ReviewCandidateAppService(
                workbenchRepository, opinionRepository, queryService);
    }

    @Test
    void generateShouldReadOnlyLatestFiftyCurrentReviewMessages() {
        Workbench workbench = workbench();
        ReviewOpinion opinion = ReviewOpinion.start(
                WORKBENCH_ID, 0L, "人工当前意见", OWNER, NOW);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(opinionRepository.findLatest(WORKBENCH_ID))
                .thenReturn(Optional.of(opinion));
        when(queryService.findCurrentPhaseConversationByOwner(
                org.mockito.ArgumentMatchers.eq(OWNER.getOwnerId()),
                org.mockito.ArgumentMatchers.eq(WORKBENCH_ID.getValue()),
                org.mockito.ArgumentMatchers.eq(WorkbenchPhase.REVIEW_REFACTOR),
                any()))
                .thenReturn(Optional.of(new PhaseConversationMessagePage(
                        "review-conversation-1", 0, workbench.getVersion(),
                        Arrays.asList(
                                new PhaseConversationMessagePage.MessageView(
                                        1L, "user", "请解释意见",
                                        "2026-08-01T17:31:00Z", null),
                                new PhaseConversationMessagePage.MessageView(
                                        2L, "assistant",
                                        "Review: App 层存在业务分支\n"
                                                + "Impact: 规则容易漂移\n"
                                                + "Suggested Change: 下沉领域策略\n"
                                                + "Affected File: agent-web::src/main/java/A.java\n"
                                                + "Required Test: 运行领域单测",
                                        "2026-08-01T17:32:00Z", "run-review-1")))));

        ReviewCandidateView result = service.generate(
                OWNER, WORKBENCH_ID);

        assertEquals(WorkbenchPhase.REVIEW_REFACTOR, result.getPhase());
        assertEquals(1L, result.getBaseOpinionVersion());
        assertEquals(0, result.getConversationGeneration());
        assertEquals(2, result.getSourceMessageCount());
        assertEquals("DETERMINISTIC_PUBLIC_REVIEW_MESSAGES_V1",
                result.getStrategy());
        assertEquals(1, result.getItems().size());
        assertEquals("App 层存在业务分支",
                result.getItems().get(0).getFinding());
        assertEquals("运行领域单测",
                result.getItems().get(0).getSuggestedTests().get(0));

        ArgumentCaptor<PhaseConversationMessageRequest> request =
                ArgumentCaptor.forClass(PhaseConversationMessageRequest.class);
        verify(queryService).findCurrentPhaseConversationByOwner(
                org.mockito.ArgumentMatchers.eq(OWNER.getOwnerId()),
                org.mockito.ArgumentMatchers.eq(WORKBENCH_ID.getValue()),
                org.mockito.ArgumentMatchers.eq(WorkbenchPhase.REVIEW_REFACTOR),
                request.capture());
        assertEquals(PhaseConversationMessageRequest.MAX_LIMIT,
                request.getValue().getLimit());
        assertEquals(null, request.getValue().getBeforeMessageId());
        verify(workbenchRepository, never()).update(any(Workbench.class));
        verify(opinionRepository, never()).add(any(ReviewOpinion.class));
    }

    @Test
    void generateShouldHideForeignOwnerBeforeReviewReads() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));

        assertThrows(WorkbenchNotFoundException.class,
                () -> service.generate(OTHER, WORKBENCH_ID));

        verifyNoInteractions(opinionRepository, queryService);
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void generateShouldUseStableUnavailableErrorWithoutPersistence() {
        Workbench workbench = workbench();
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(queryService.findCurrentPhaseConversationByOwner(
                org.mockito.ArgumentMatchers.eq(OWNER.getOwnerId()),
                org.mockito.ArgumentMatchers.eq(WORKBENCH_ID.getValue()),
                org.mockito.ArgumentMatchers.eq(WorkbenchPhase.REVIEW_REFACTOR),
                any()))
                .thenReturn(Optional.<PhaseConversationMessagePage>empty());

        ReviewApplicationException failure = assertThrows(
                ReviewApplicationException.class,
                () -> service.generate(OWNER, WORKBENCH_ID));

        assertEquals(ReviewApplicationErrorCode.CANDIDATE_SOURCE_UNAVAILABLE,
                failure.getCode());
        verifyNoInteractions(opinionRepository);
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void generateShouldRejectRestartRaceAndLeaveOpinionUnchanged() {
        Workbench workbench = workbench();
        ReviewOpinion opinion = ReviewOpinion.start(
                WORKBENCH_ID, 0L, "不能改变的人工意见", OWNER, NOW);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(opinionRepository.findLatest(WORKBENCH_ID))
                .thenReturn(Optional.of(opinion));
        when(queryService.findCurrentPhaseConversationByOwner(
                org.mockito.ArgumentMatchers.eq(OWNER.getOwnerId()),
                org.mockito.ArgumentMatchers.eq(WORKBENCH_ID.getValue()),
                org.mockito.ArgumentMatchers.eq(WorkbenchPhase.REVIEW_REFACTOR),
                any()))
                .thenReturn(Optional.of(new PhaseConversationMessagePage(
                        "review-conversation-2", 1, workbench.getVersion(),
                        Collections.<PhaseConversationMessagePage.MessageView>emptyList())));

        assertThrows(WorkbenchDomainException.class,
                () -> service.generate(OWNER, WORKBENCH_ID));

        assertEquals(1L, opinion.getVersion());
        assertEquals("不能改变的人工意见", opinion.getContent());
        verify(opinionRepository, never()).add(any(ReviewOpinion.class));
        verify(workbenchRepository, never()).update(any(Workbench.class));
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
                10);
        Workbench workbench = Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "Review code",
                AgentType.CODEX, "test", scope,
                new WorkspaceSnapshotReference(
                        "snapshot-review",
                        WorkspaceTopology.of("/workspace", selection)
                                .getTopologyHash(),
                        repeat('a'), 1),
                NOW.minusSeconds(2));
        workbench.bindConversation(
                WorkbenchPhase.REVIEW_REFACTOR,
                "review-conversation-1", OWNER, NOW.minusSeconds(1));
        return workbench;
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        Arrays.fill(values, value);
        return new String(values);
    }
}
