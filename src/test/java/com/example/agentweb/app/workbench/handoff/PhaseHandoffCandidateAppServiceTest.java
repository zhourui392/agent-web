package com.example.agentweb.app.workbench.handoff;

import com.example.agentweb.app.workbench.query.PhaseConversationMessagePage;
import com.example.agentweb.app.workbench.query.PhaseConversationMessageRequest;
import com.example.agentweb.app.workbench.query.WorkbenchQueryService;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.Decision;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.OpenQuestion;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.PhaseHandoffRepository;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
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
 * Handoff Candidate 只读应用用例的 Owner、投影与有界消息编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseHandoffCandidateAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T16:30:00Z");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final OwnerReference OTHER =
            OwnerReference.of("owner-2", "Other");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final WorkbenchPhase PHASE =
            WorkbenchPhase.SOLUTION_DESIGN;

    private WorkbenchRepository workbenchRepository;
    private PhaseHandoffRepository handoffRepository;
    private WorkbenchQueryService queryService;
    private PhaseHandoffCandidateAppService service;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        handoffRepository = mock(PhaseHandoffRepository.class);
        queryService = mock(WorkbenchQueryService.class);
        service = new PhaseHandoffCandidateAppService(
                workbenchRepository, handoffRepository, queryService);
    }

    @Test
    void generateShouldReadOnlyCurrentBoundedPublicProjectionAndReturnSafeCandidate() {
        Workbench workbench = workbench();
        PhaseHandoff handoff = handoff(workbench.getRepositoryScope());
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(handoffRepository.find(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.of(handoff));
        when(queryService.findCurrentPhaseConversationByOwner(
                org.mockito.ArgumentMatchers.eq(OWNER.getOwnerId()),
                org.mockito.ArgumentMatchers.eq(WORKBENCH_ID.getValue()),
                org.mockito.ArgumentMatchers.eq(PHASE), any()))
                .thenReturn(Optional.of(new PhaseConversationMessagePage(
                        "conversation-1", 0, workbench.getVersion(),
                        Arrays.asList(
                                new PhaseConversationMessagePage.MessageView(
                                        1L, "user", "请总结", "2026-08-01T16:31:00Z", null),
                                new PhaseConversationMessagePage.MessageView(
                                        2L, "assistant",
                                        "完成\nDecision: 保持分层\n"
                                                + "Pinned File: agent-web::docs/design.md",
                                        "2026-08-01T16:32:00Z", "run-1")))));

        PhaseHandoffCandidateProjection result = service.generate(
                OWNER, WORKBENCH_ID, PHASE);

        assertEquals(PHASE, result.getSourcePhase());
        assertEquals(0L, result.getBaseHandoffVersion());
        assertEquals(0, result.getConversationGeneration());
        assertEquals(2, result.getSourceMessageCount());
        assertEquals("DETERMINISTIC_PUBLIC_MESSAGES_V1",
                result.getStrategy());
        assertEquals("完成\nDecision: 保持分层\n"
                        + "Pinned File: agent-web::docs/design.md",
                result.getSummary());
        assertEquals("保持分层", result.getDecisions().get(0).getText());
        assertEquals("agent-web",
                result.getPinnedFiles().get(0).getRepositoryKey());
        assertEquals("run-1",
                result.getReferencedRuns().get(0).getRunId());
        assertEquals(PHASE,
                result.getReferencedRuns().get(0).getPhase());
        assertEquals("Run run-1 (SOLUTION_DESIGN)",
                result.getReferencedRuns().get(0).getSafeSummary());

        ArgumentCaptor<PhaseConversationMessageRequest> request =
                ArgumentCaptor.forClass(PhaseConversationMessageRequest.class);
        verify(queryService).findCurrentPhaseConversationByOwner(
                org.mockito.ArgumentMatchers.eq(OWNER.getOwnerId()),
                org.mockito.ArgumentMatchers.eq(WORKBENCH_ID.getValue()),
                org.mockito.ArgumentMatchers.eq(PHASE), request.capture());
        assertEquals(PhaseConversationMessageRequest.MAX_LIMIT,
                request.getValue().getLimit());
        assertEquals(null, request.getValue().getBeforeMessageId());
        verify(workbenchRepository, never()).update(any(Workbench.class));
        verify(handoffRepository, never()).add(any(PhaseHandoff.class));
        verify(handoffRepository, never()).update(any(PhaseHandoff.class));
    }

    @Test
    void generateShouldHideForeignWorkbenchBeforeReadingConversationOrHandoff() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench()));

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.generate(OTHER, WORKBENCH_ID, PHASE));

        assertEquals(HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND,
                failure.getCode());
        verifyNoInteractions(handoffRepository, queryService);
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void generateShouldFailWithoutCurrentPublicProjectionAndNeverPersistCandidate() {
        Workbench workbench = workbench();
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(handoffRepository.find(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.<PhaseHandoff>empty());
        when(queryService.findCurrentPhaseConversationByOwner(
                org.mockito.ArgumentMatchers.eq(OWNER.getOwnerId()),
                org.mockito.ArgumentMatchers.eq(WORKBENCH_ID.getValue()),
                org.mockito.ArgumentMatchers.eq(PHASE), any()))
                .thenReturn(Optional.<PhaseConversationMessagePage>empty());

        HandoffApplicationException failure = assertThrows(
                HandoffApplicationException.class,
                () -> service.generate(OWNER, WORKBENCH_ID, PHASE));

        assertEquals(HandoffApplicationErrorCode.CANDIDATE_SOURCE_UNAVAILABLE,
                failure.getCode());
        verify(workbenchRepository, never()).update(any(Workbench.class));
        verify(handoffRepository, never()).add(any(PhaseHandoff.class));
        verify(handoffRepository, never()).update(any(PhaseHandoff.class));
    }

    @Test
    void generateShouldRejectConversationRestartRaceWithoutChangingManualHandoff() {
        Workbench workbench = workbench();
        PhaseHandoff handoff = handoff(workbench.getRepositoryScope());
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(handoffRepository.find(WORKBENCH_ID, PHASE))
                .thenReturn(Optional.of(handoff));
        when(queryService.findCurrentPhaseConversationByOwner(
                org.mockito.ArgumentMatchers.eq(OWNER.getOwnerId()),
                org.mockito.ArgumentMatchers.eq(WORKBENCH_ID.getValue()),
                org.mockito.ArgumentMatchers.eq(PHASE), any()))
                .thenReturn(Optional.of(new PhaseConversationMessagePage(
                        "conversation-2", 1, workbench.getVersion(),
                        Collections.<PhaseConversationMessagePage.MessageView>emptyList())));

        assertThrows(WorkbenchDomainException.class,
                () -> service.generate(OWNER, WORKBENCH_ID, PHASE));

        assertEquals("manual", handoff.getSummary());
        assertEquals(0L, handoff.getVersion());
        verify(workbenchRepository, never()).update(any(Workbench.class));
        verify(handoffRepository, never()).add(any(PhaseHandoff.class));
        verify(handoffRepository, never()).update(any(PhaseHandoff.class));
    }

    private static Workbench workbench() {
        RepositoryScope scope = repositoryScope();
        Workbench workbench = Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "实现工作台",
                AgentType.CODEX, "test", scope,
                new WorkspaceSnapshotReference(
                        "snapshot-1", WorkspaceTopology.of(
                                "/workspace", RepositorySelection.of(
                                        "agent-web",
                                        Collections.singletonList("agent-web")))
                                .getTopologyHash(), repeat('a'), 1),
                NOW);
        workbench.bindConversation(
                PHASE, "conversation-1", OWNER, NOW.plusSeconds(1));
        return workbench;
    }

    private static PhaseHandoff handoff(RepositoryScope scope) {
        return PhaseHandoff.create(
                WORKBENCH_ID, PHASE, "manual",
                Collections.<Decision>emptyList(),
                Collections.<OpenQuestion>emptyList(),
                Collections.<DocumentReference>emptyList(),
                Collections.<WorkbenchRunReference>emptyList(),
                scope, OWNER, NOW);
    }

    private static RepositoryScope repositoryScope() {
        return RepositoryScope.create(
                "/workspace",
                RepositorySelection.of(
                        "agent-web", Collections.singletonList("agent-web")),
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('1'), false)),
                10);
    }

    private static String repeat(char value) {
        char[] content = new char[64];
        Arrays.fill(content, value);
        return new String(content);
    }
}
