package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.handoff.AcceptHandoffReceptionCommand;
import com.example.agentweb.app.workbench.handoff.HandoffApplicationErrorCode;
import com.example.agentweb.app.workbench.handoff.HandoffApplicationException;
import com.example.agentweb.app.workbench.handoff.HandoffCollectionDiff;
import com.example.agentweb.app.workbench.handoff.HandoffDiffSummary;
import com.example.agentweb.app.workbench.handoff.HandoffReceptionProjection;
import com.example.agentweb.app.workbench.handoff.HandoffSourcePreview;
import com.example.agentweb.app.workbench.handoff.PhaseHandoffContentCommand;
import com.example.agentweb.app.workbench.handoff.PhaseHandoffCandidateAppService;
import com.example.agentweb.app.workbench.handoff.PhaseHandoffCandidateProjection;
import com.example.agentweb.app.workbench.handoff.PhaseHandoffOwnerService;
import com.example.agentweb.app.workbench.handoff.PhaseHandoffProjection;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.Decision;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HandoffCandidateConversation;
import com.example.agentweb.domain.workbench.HandoffCandidateMessage;
import com.example.agentweb.domain.workbench.HandoffReception;
import com.example.agentweb.domain.workbench.OpenQuestion;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.PhaseHandoffCandidateGenerator;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import com.example.agentweb.interfaces.GlobalExceptionHandler;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase Handoff Owner HTTP API 的输入收敛与安全输出契约测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@WebMvcTest(PhaseHandoffController.class)
@Import({GlobalExceptionHandler.class, WorkbenchExceptionHandler.class})
class PhaseHandoffControllerTest {

    private static final String OWNER_ID = "owner-1";
    private static final String OWNER_NAME = "Alex";
    private static final String WORKBENCH_ID = "workbench-1";
    private static final String HANDOFF_ROUTE =
            "/api/workbenches/{id}/phases/{phase}/handoff";
    private static final String SOURCE_ROUTE =
            "/api/workbenches/{id}/phases/{phase}/handoff-source";
    private static final String RECEPTION_ROUTE =
            "/api/workbenches/{id}/phases/{phase}/handoff-receptions";
    private static final String CANDIDATE_ROUTE =
            "/api/workbenches/{id}/phases/{phase}/handoff-candidates";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private PhaseHandoffOwnerService service;

    @MockBean
    private PhaseHandoffCandidateAppService candidateService;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void authenticateOwner() {
        when(currentUserProvider.currentUserId()).thenReturn(OWNER_ID);
        when(currentUserProvider.currentUserName()).thenReturn(OWNER_NAME);
    }

    @Test
    void getShouldUseCurrentOwnerAndReturnOnlySafeHandoffProjection()
            throws Exception {
        PhaseHandoffProjection view = PhaseHandoffProjection.from(
                handoff("需求确认", 0L), false);
        when(service.get(any(), any(), any())).thenReturn(view);

        mvc.perform(get(HANDOFF_ROUTE, WORKBENCH_ID,
                        "requirement_analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourcePhase")
                        .value("REQUIREMENT_ANALYSIS"))
                .andExpect(jsonPath("$.summary").value("需求确认"))
                .andExpect(jsonPath("$.decisions[0].text")
                        .value("使用 DDD"))
                .andExpect(jsonPath("$.decisions[0].rationale")
                        .value("边界清晰"))
                .andExpect(jsonPath("$.decisions[0].status")
                        .doesNotExist())
                .andExpect(jsonPath("$.openQuestions[0].ownerHint")
                        .value("owner"))
                .andExpect(jsonPath("$.pinnedFiles[0].repositoryKey")
                        .value("agent-web"))
                .andExpect(jsonPath("$.referencedRuns[0].runId")
                        .value("run-1"))
                .andExpect(jsonPath("$.referencedRuns[0].phase")
                        .value("REQUIREMENT_ANALYSIS"))
                .andExpect(jsonPath("$.referencedRuns[0].safeSummary")
                        .value("safe summary"))
                .andExpect(jsonPath("$.referencedRuns[0].workbenchId")
                        .doesNotExist())
                .andExpect(jsonPath("$.updatedBy").doesNotExist())
                .andExpect(jsonPath("$.readOnly").value(false))
                .andExpect(content().string(not(containsString("owner-1"))))
                .andExpect(content().string(not(containsString("/workspace"))));

        verify(service).get(
                OwnerReference.of(OWNER_ID, OWNER_NAME),
                WorkbenchId.of(WORKBENCH_ID),
                WorkbenchPhase.REQUIREMENT_ANALYSIS);
    }

    @Test
    void putShouldRequireVersionAndConvertOnlyFiveEditableFields()
            throws Exception {
        PhaseHandoffProjection saved = PhaseHandoffProjection.from(
                handoff("保存内容", 1L), false);
        when(service.save(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(saved);
        String request = validHandoffRequest();

        mvc.perform(put(HANDOFF_ROUTE, WORKBENCH_ID, "solution_design")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("保存内容"));

        ArgumentCaptor<PhaseHandoffContentCommand> content =
                ArgumentCaptor.forClass(PhaseHandoffContentCommand.class);
        verify(service).save(
                org.mockito.ArgumentMatchers.eq(
                        OwnerReference.of(OWNER_ID, OWNER_NAME)),
                org.mockito.ArgumentMatchers.eq(
                        WorkbenchId.of(WORKBENCH_ID)),
                org.mockito.ArgumentMatchers.eq(
                        WorkbenchPhase.SOLUTION_DESIGN),
                org.mockito.ArgumentMatchers.eq(0L), content.capture());
        assertEquals("manual summary", content.getValue().getSummary());
        assertEquals("decision", content.getValue().getDecisions().get(0)
                .getText());
        assertEquals("question", content.getValue().getOpenQuestions().get(0)
                .getText());
        assertEquals("docs/design.md", content.getValue().getPinnedFiles()
                .get(0).getRelativePath());
        assertEquals(Collections.singletonList("run-1"),
                content.getValue().getReferencedRunIds());
    }

    @Test
    void invalidHeaderPhaseAndUnknownOrInternalBodyFieldsShouldFailClosed()
            throws Exception {
        String request = validHandoffRequest();
        mvc.perform(put(HANDOFF_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_HANDOFF_REQUEST_INVALID"));
        mvc.perform(put(HANDOFF_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("If-Match", "-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());
        mvc.perform(put(HANDOFF_ROUTE, WORKBENCH_ID, "unknown")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());
        mvc.perform(put(HANDOFF_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.substring(0, request.length() - 1)
                                + ",\"updatedBy\":\"owner-1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(HANDOFF_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace(
                                "{\"runId\":\"run-1\"}",
                                "{\"runId\":\"run-1\","
                                        + "\"safeSummary\":\"forged\"}")))
                .andExpect(status().isBadRequest());

        verify(service, never()).save(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void versionConflictShouldReturnStableCodeAndSafeCurrentProjection()
            throws Exception {
        PhaseHandoffProjection current = PhaseHandoffProjection.from(
                handoff("remote", 2L), false);
        when(service.save(any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(1L), any()))
                .thenThrow(new HandoffApplicationException(
                        HandoffApplicationErrorCode.VERSION_CONFLICT,
                        "workbench handoff version conflict", current));

        mvc.perform(put(HANDOFF_ROUTE, WORKBENCH_ID,
                        "REQUIREMENT_ANALYSIS")
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHandoffRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_HANDOFF_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.current.summary").value("remote"))
                .andExpect(jsonPath("$.current.updatedBy").doesNotExist())
                .andExpect(jsonPath(
                        "$.current.referencedRuns[0].workbenchId")
                        .doesNotExist())
                .andExpect(content().string(not(containsString("owner-1"))));
    }

    @Test
    void sourceShouldReturnLatestReceptionExactAcceptedAndDiff()
            throws Exception {
        PhaseHandoffProjection accepted = PhaseHandoffProjection.from(
                handoff("accepted", 0L), false);
        PhaseHandoffProjection latest = PhaseHandoffProjection.from(
                handoff("latest", 1L), false);
        HandoffReceptionProjection reception =
                new HandoffReceptionProjection(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        accepted.getVersion(), accepted.getContentHash(),
                        1_775_210_399_000L);
        HandoffDiffSummary diff = new HandoffDiffSummary(
                true, new HandoffCollectionDiff(1, 0),
                new HandoffCollectionDiff(0, 0),
                new HandoffCollectionDiff(1, 0),
                new HandoffCollectionDiff(0, 1));
        when(service.source(any(), any(), any())).thenReturn(
                new HandoffSourcePreview(
                        WorkbenchPhase.SOLUTION_DESIGN, latest, reception,
                        accepted, true, diff));

        mvc.perform(get(SOURCE_ROUTE, WORKBENCH_ID, "solution_design"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetPhase")
                        .value("SOLUTION_DESIGN"))
                .andExpect(jsonPath("$.latestSource.summary")
                        .value("latest"))
                .andExpect(jsonPath("$.reception.sourceVersion")
                        .value(0))
                .andExpect(jsonPath("$.acceptedSource.summary")
                        .value("accepted"))
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.diff.summaryChanged").value(true))
                .andExpect(jsonPath("$.diff.decisions.added").value(1))
                .andExpect(jsonPath("$.diff.referencedRuns.removed")
                        .value(1))
                .andExpect(jsonPath("$.reception.acceptedBy")
                        .doesNotExist());
    }

    @Test
    void acceptShouldMapRequestAndUseDedicatedSourceChangedConflict()
            throws Exception {
        PhaseHandoff source = handoff("source", 3L);
        HandoffReception reception = HandoffReception.accept(
                WorkbenchId.of(WORKBENCH_ID),
                WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                source.getVersion(), source.getContentHash(),
                OwnerReference.of(OWNER_ID, OWNER_NAME),
                Instant.parse("2026-08-01T15:00:00Z"));
        when(service.accept(any(), any()))
                .thenReturn(HandoffReceptionProjection.from(reception))
                .thenThrow(new HandoffApplicationException(
                        HandoffApplicationErrorCode.SOURCE_CHANGED,
                        "workbench handoff source changed"));
        String request = "{\"sourcePhase\":\"REQUIREMENT_ANALYSIS\","
                + "\"sourceVersion\":3,\"sourceHash\":\""
                + source.getContentHash() + "\"}";

        mvc.perform(post(RECEPTION_ROUTE, WORKBENCH_ID, "solution_design")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourcePhase")
                        .value("REQUIREMENT_ANALYSIS"))
                .andExpect(jsonPath("$.sourceVersion").value(3))
                .andExpect(jsonPath("$.acceptedAt")
                        .value(1_785_596_400_000L))
                .andExpect(jsonPath("$.acceptedBy").doesNotExist());

        ArgumentCaptor<AcceptHandoffReceptionCommand> command =
                ArgumentCaptor.forClass(
                        AcceptHandoffReceptionCommand.class);
        verify(service).accept(
                org.mockito.ArgumentMatchers.eq(
                        OwnerReference.of(OWNER_ID, OWNER_NAME)),
                command.capture());
        assertEquals(WorkbenchPhase.SOLUTION_DESIGN,
                command.getValue().getTargetPhase());
        assertEquals(WorkbenchPhase.REQUIREMENT_ANALYSIS,
                command.getValue().getSourcePhase());

        mvc.perform(post(RECEPTION_ROUTE, WORKBENCH_ID, "solution_design")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_HANDOFF_SOURCE_CHANGED"));
    }

    @Test
    void acceptShouldAllowInitialSourceVersionZero() throws Exception {
        HandoffReceptionProjection reception =
                new HandoffReceptionProjection(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        0L, repeat('a'), 1_785_596_400_000L);
        when(service.accept(any(), any())).thenReturn(reception);

        mvc.perform(post(RECEPTION_ROUTE, WORKBENCH_ID, "solution_design")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourcePhase\":\"REQUIREMENT_ANALYSIS\","
                                + "\"sourceVersion\":0,\"sourceHash\":\""
                                + repeat('a') + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceVersion").value(0));

        ArgumentCaptor<AcceptHandoffReceptionCommand> command =
                ArgumentCaptor.forClass(
                        AcceptHandoffReceptionCommand.class);
        verify(service).accept(
                org.mockito.ArgumentMatchers.eq(
                        OwnerReference.of(OWNER_ID, OWNER_NAME)),
                command.capture());
        assertEquals(0L, command.getValue().getSourceVersion());
    }

    @Test
    void candidateShouldUseCurrentOwnerAndReturnOnlySafeEphemeralProjection()
            throws Exception {
        when(candidateService.generate(any(), any(), any()))
                .thenReturn(candidateProjection());

        mvc.perform(post(CANDIDATE_ROUTE, WORKBENCH_ID, "solution_design")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourcePhase")
                        .value("SOLUTION_DESIGN"))
                .andExpect(jsonPath("$.baseHandoffVersion").value(0))
                .andExpect(jsonPath("$.conversationGeneration").value(0))
                .andExpect(jsonPath("$.sourceMessageCount").value(1))
                .andExpect(jsonPath("$.strategy").value(
                        "DETERMINISTIC_PUBLIC_MESSAGES_V1"))
                .andExpect(jsonPath("$.summary").value(
                        "结论\nDecision: 使用 DDD"))
                .andExpect(jsonPath("$.decisions[0].text")
                        .value("使用 DDD"))
                .andExpect(jsonPath("$.openQuestions").isArray())
                .andExpect(jsonPath("$.pinnedFiles").isArray())
                .andExpect(jsonPath("$.referencedRuns[0].runId")
                        .value("run-design-1"))
                .andExpect(jsonPath("$.referencedRuns[0].phase")
                        .value("SOLUTION_DESIGN"))
                .andExpect(jsonPath("$.referencedRuns[0].safeSummary")
                        .value("Run run-design-1 (SOLUTION_DESIGN)"))
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.workbenchId").doesNotExist())
                .andExpect(jsonPath("$.conversationId").doesNotExist())
                .andExpect(content().string(not(containsString(OWNER_ID))))
                .andExpect(content().string(not(containsString("/workspace"))));

        verify(candidateService).generate(
                OwnerReference.of(OWNER_ID, OWNER_NAME),
                WorkbenchId.of(WORKBENCH_ID),
                WorkbenchPhase.SOLUTION_DESIGN);
    }

    @Test
    void candidateShouldRejectUnknownBodyAndUseStableUnavailableContract()
            throws Exception {
        mvc.perform(post(CANDIDATE_ROUTE, WORKBENCH_ID, "solution_design")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autoSave\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_HANDOFF_REQUEST_INVALID"));
        verify(candidateService, never()).generate(any(), any(), any());

        when(candidateService.generate(any(), any(), any()))
                .thenThrow(new HandoffApplicationException(
                        HandoffApplicationErrorCode.CANDIDATE_SOURCE_UNAVAILABLE,
                        "current phase public conversation is unavailable"));
        mvc.perform(post(CANDIDATE_ROUTE, WORKBENCH_ID, "solution_design")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_HANDOFF_CANDIDATE_SOURCE_UNAVAILABLE"));
    }

    @Test
    void secretDetectionShouldUseStableCodeWithoutEchoingSecret()
            throws Exception {
        String secret = "password=correct-horse-battery-staple";
        when(service.save(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.HANDOFF_SECRET_DETECTED,
                        "handoff content contains secret-like material"));

        mvc.perform(put(HANDOFF_ROUTE, WORKBENCH_ID,
                        "REQUIREMENT_ANALYSIS")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHandoffRequest().replace(
                                "manual summary", secret)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(
                        "WORKBENCH_HANDOFF_SECRET_DETECTED"))
                .andExpect(content().string(not(containsString(secret))));
    }

    @Test
    void notFoundAndArchivedFailuresShouldKeepWorkbenchContracts()
            throws Exception {
        when(service.get(any(), any(), any()))
                .thenThrow(new HandoffApplicationException(
                        HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND,
                        "workbench was not found"))
                .thenThrow(new HandoffApplicationException(
                        HandoffApplicationErrorCode.HANDOFF_NOT_FOUND,
                        "handoff was not found"));
        when(service.save(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenThrow(new WorkbenchDomainException(
                        WorkbenchErrorCode.ARCHIVED, "archived"));

        mvc.perform(get(HANDOFF_ROUTE, "foreign", "IMPLEMENT_TEST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_NOT_FOUND"));
        mvc.perform(get(HANDOFF_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_HANDOFF_NOT_FOUND"));
        mvc.perform(put(HANDOFF_ROUTE, WORKBENCH_ID, "IMPLEMENT_TEST")
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validHandoffRequest()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_ARCHIVED"));
    }

    private static String validHandoffRequest() {
        return "{\"summary\":\"manual summary\","
                + "\"decisions\":[{\"text\":\"decision\","
                + "\"rationale\":null}],"
                + "\"openQuestions\":[{\"text\":\"question\","
                + "\"ownerHint\":null}],"
                + "\"pinnedFiles\":[{\"repositoryKey\":\"agent-web\","
                + "\"relativePath\":\"docs/design.md\"}],"
                + "\"referencedRuns\":[{\"runId\":\"run-1\"}]}";
    }

    private static PhaseHandoff handoff(String summary, long version) {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('1'), false)),
                50);
        PhaseHandoff handoff = PhaseHandoff.create(
                WorkbenchId.of(WORKBENCH_ID),
                WorkbenchPhase.REQUIREMENT_ANALYSIS, summary,
                Collections.singletonList(
                        Decision.confirmed("使用 DDD", "边界清晰")),
                Collections.singletonList(
                        OpenQuestion.of("是否补集成测试", "owner")),
                Collections.singletonList(
                        DocumentReference.of("agent-web", "README.md")),
                Collections.singletonList(WorkbenchRunReference.of(
                        "run-1", WorkbenchId.of(WORKBENCH_ID),
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "safe summary")), scope,
                OwnerReference.of(OWNER_ID, OWNER_NAME),
                Instant.parse("2026-08-01T15:00:00Z"));
        for (long current = 0L; current < version; current++) {
            handoff.update(
                    current, summary, handoff.getDecisions(),
                    handoff.getOpenQuestions(), handoff.getPinnedFiles(),
                    handoff.getReferencedRuns(), scope,
                    OwnerReference.of(OWNER_ID, OWNER_NAME),
                    Instant.parse("2026-08-01T15:00:00Z")
                            .plusSeconds(current + 1L));
        }
        return handoff;
    }

    private static PhaseHandoffCandidateProjection candidateProjection() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('1'), false)),
                50);
        OwnerReference owner = OwnerReference.of(OWNER_ID, OWNER_NAME);
        Workbench workbench = Workbench.create(
                WorkbenchId.of(WORKBENCH_ID), owner,
                "Workbench", "实现工作台", AgentType.CODEX, "test",
                scope, new WorkspaceSnapshotReference(
                        "snapshot-1",
                        WorkspaceTopology.of("/workspace", selection)
                                .getTopologyHash(),
                        repeat('a'), 1),
                Instant.parse("2026-08-01T15:00:00Z"));
        workbench.bindConversation(
                WorkbenchPhase.SOLUTION_DESIGN, "conversation-1", owner,
                Instant.parse("2026-08-01T15:00:01Z"));
        return PhaseHandoffCandidateProjection.from(
                new PhaseHandoffCandidateGenerator().generate(
                        owner, workbench, WorkbenchPhase.SOLUTION_DESIGN,
                        java.util.Optional.<PhaseHandoff>empty(),
                        HandoffCandidateConversation.capture(
                                "conversation-1", 0,
                                Collections.singletonList(
                                        HandoffCandidateMessage.publicMessage(
                                                1L, "assistant",
                                                "结论\nDecision: 使用 DDD",
                                                "run-design-1")))));
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        Arrays.fill(values, value);
        return new String(values);
    }
}
