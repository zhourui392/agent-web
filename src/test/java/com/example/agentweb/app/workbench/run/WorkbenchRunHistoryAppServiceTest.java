package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEvent;
import com.example.agentweb.app.chatrun.ChatRunEventStore;
import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workbench Run 历史、事件与能力查询的 Owner-first 编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunHistoryAppServiceTest {

    private WorkbenchRunAccessResolver accessResolver;
    private WorkbenchRunHistoryQuery historyQuery;
    private ChatRunEventStore eventStore;
    private WorkbenchRunHistoryAppService service;

    @BeforeEach
    void setUp() {
        accessResolver = mock(WorkbenchRunAccessResolver.class);
        historyQuery = mock(WorkbenchRunHistoryQuery.class);
        eventStore = mock(ChatRunEventStore.class);
        service = new WorkbenchRunHistoryAppService(
                accessResolver, historyQuery, eventStore);
    }

    @Test
    void listShouldAuthorizeOwnerBeforeReadingProjection() {
        Workbench workbench = WorkbenchRunTestFixtures.workbench();
        WorkbenchRunListRequest request = new WorkbenchRunListRequest(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, null, 20);
        WorkbenchRunListPage expected = new WorkbenchRunListPage(
                Collections.emptyList(), null);
        when(accessResolver.requireOwned(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID))
                .thenReturn(workbench);
        when(historyQuery.list(
                WorkbenchRunTestFixtures.WORKBENCH_ID,
                workbench.getRepositoryScope().getScopeHash(), request))
                .thenReturn(expected);

        WorkbenchRunListPage result = service.list(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID, request);

        assertEquals(expected, result);
        InOrder order = inOrder(accessResolver, historyQuery);
        order.verify(accessResolver).requireOwned(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID);
        order.verify(historyQuery).list(
                WorkbenchRunTestFixtures.WORKBENCH_ID,
                workbench.getRepositoryScope().getScopeHash(), request);
    }

    @Test
    void missingExactDetailShouldRemainIndistinguishableFromUnauthorizedRun() {
        AuthorizedWorkbenchRun authorized = authorize(
                WorkbenchRunTestFixtures.runningRun());
        when(historyQuery.findDetail(
                WorkbenchRunTestFixtures.WORKBENCH_ID,
                authorized.getWorkbench().getRepositoryScope().getScopeHash(),
                "run-1")).thenReturn(Optional.empty());

        assertThrows(WorkbenchRunNotFoundException.class,
                () -> service.detail(
                        WorkbenchRunTestFixtures.OWNER,
                        WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1"));
    }

    @Test
    void eventPageShouldReuseSseEnvelopeAndRemainBounded() {
        AuthorizedWorkbenchRun authorized = authorize(
                WorkbenchRunTestFixtures.runningRun());
        ChatRun run = authorized.getRun();
        run.allocateEventSequence(2, WorkbenchRunTestFixtures.NOW);
        ChatRunEvent first = event(run.getId(), 1L, "agent_chunk",
                "{\"content\":\"hello\"}");
        when(eventStore.findEarliestSequence(run.getId())).thenReturn(1L);
        when(eventStore.findAfterThrough(
                run.getId(), 0L, 2L, 1))
                .thenReturn(Collections.singletonList(first));

        WorkbenchRunEventPage page = service.events(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1",
                new WorkbenchRunEventPageRequest(0L, 1));

        assertEquals(1, page.getEvents().size());
        assertEquals(1L, page.getThrough());
        assertEquals(2L, page.getLastEventSeq());
        assertEquals(1L, page.getEarliestRetainedSeq());
        assertEquals(true, page.isHasMore());
        assertEquals("agent_chunk", page.getEvents().get(0).getEventType());
        org.junit.jupiter.api.Assertions.assertTrue(
                page.getEvents().get(0).getPayload().contains(
                        "\"schemaVersion\":\"workbench-run-event@1\""));
        org.junit.jupiter.api.Assertions.assertTrue(
                page.getEvents().get(0).getPayload().contains(
                        "\"workbenchId\":\"workbench-1\""));
    }

    @Test
    void expiredEventCursorShouldFailBeforeReadingEventBodies() {
        AuthorizedWorkbenchRun authorized = authorize(
                WorkbenchRunTestFixtures.runningRun());
        authorized.getRun().allocateEventSequence(
                10, WorkbenchRunTestFixtures.NOW);
        when(eventStore.findEarliestSequence(authorized.getRun().getId()))
                .thenReturn(5L);

        assertThrows(WorkbenchRunCursorExpiredException.class,
                () -> service.events(
                        WorkbenchRunTestFixtures.OWNER,
                        WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1",
                        new WorkbenchRunEventPageRequest(2L, 50)));

        verifyNoInteractions(historyQuery);
    }

    @Test
    void capabilityShouldExposeOnlyFrozenTraceableFacts() {
        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                "policy-1", "requirement-analysis", "1", repeat('a'),
                Collections.singletonList(new ResolvedRuleBinding(
                        "platform/safety", "1", "PLATFORM", repeat('b'),
                        true, "安全规则")),
                Collections.singletonList(new ResolvedSkillBinding(
                        "code-search", "2", "PLATFORM", repeat('c'),
                        "TRUSTED")),
                Collections.singletonList(new ResolvedMcpServerBinding(
                        "repository-query", "3", repeat('d'),
                        CapabilityAccess.READ, "STDIO")),
                Collections.singletonList(new RejectedCapability(
                        "production-write", "ACCESS_FORBIDDEN")),
                "codex-compatible");
        AuthorizedWorkbenchRun authorized = AuthorizedWorkbenchRun.verified(
                WorkbenchRunTestFixtures.workbench(),
                WorkbenchRunTestFixtures.snapshot(binding),
                WorkbenchRunTestFixtures.runningRun());
        when(accessResolver.requireAuthorized(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1"))
                .thenReturn(authorized);

        WorkbenchRunCapabilityView view = service.capability(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1");

        assertEquals("run-1", view.getRunId());
        assertEquals(authorized.getSnapshot().getCapabilityBinding()
                .getBindingHash(), view.getBindingHash());
        assertEquals(0L, view.getOverrideVersion());
        assertEquals(1, view.getRules().size());
        assertEquals("platform/safety", view.getRules().get(0).getId());
        assertEquals("安全规则", view.getRules().get(0).getSafeSummary());
        assertEquals("code-search", view.getSkills().get(0).getId());
        assertEquals("repository-query", view.getMcpServers().get(0).getId());
        assertEquals("READ", view.getMcpServers().get(0).getAccess());
        assertEquals("production-write", view.getRejected().get(0).getId());
        assertEquals(authorized.getSnapshot().getRepositoryScopeHash(),
                view.getRepositoryScopeHash());
        assertEquals("repo", view.getPrimaryRepositoryKey());
        assertEquals(1, view.getRepositories().size());
        assertEquals("repo", view.getRepositories().get(0).getRepositoryKey());
        assertEquals("repo", view.getRepositories().get(0).getRelativePath());
        assertEquals(true, view.getRepositories().get(0).isPrimary());
        assertEquals("READ", view.getRepositories().get(0).getAccess());
    }

    private AuthorizedWorkbenchRun authorize(ChatRun run) {
        AuthorizedWorkbenchRun authorized = AuthorizedWorkbenchRun.verified(
                WorkbenchRunTestFixtures.workbench(),
                WorkbenchRunTestFixtures.snapshot(), run);
        when(accessResolver.requireAuthorized(
                WorkbenchRunTestFixtures.OWNER,
                WorkbenchRunTestFixtures.WORKBENCH_ID, "run-1"))
                .thenReturn(authorized);
        return authorized;
    }

    private ChatRunEvent event(
            ChatRunId runId, long sequence, String eventType,
            String payload) {
        return new ChatRunEvent(
                runId, sequence, eventType, payload,
                payload.getBytes(StandardCharsets.UTF_8).length,
                WorkbenchRunTestFixtures.NOW.plusSeconds(sequence));
    }

    private String repeat(char value) {
        char[] characters = new char[64];
        Arrays.fill(characters, value);
        return new String(characters);
    }
}
