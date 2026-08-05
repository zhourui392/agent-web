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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dynamic Stage Run 历史、事件与能力查询的 Owner-first 编排测试。
 *
 * @author alex
 * @since 2026-08-05
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
    void should_AuthorizeOwnerBeforeReadingStageHistory_When_Listing() {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        Workbench workbench = fixture.workbench();
        WorkbenchRunListRequest request = new WorkbenchRunListRequest(
                WorkbenchStageRunTestFixtures.STAGE_INSTANCE_IDENTIFIER,
                null, 20);
        WorkbenchRunListPage expected = new WorkbenchRunListPage(
                Collections.emptyList(), null);
        when(accessResolver.requireOwned(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID))
                .thenReturn(workbench);
        when(historyQuery.list(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                workbench.getRepositoryScope().getScopeHash(), request))
                .thenReturn(expected);

        // When
        WorkbenchRunListPage result = service.list(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID, request);

        // Then
        assertEquals(expected, result);
        InOrder order = inOrder(accessResolver, historyQuery);
        order.verify(accessResolver).requireOwned(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID);
        order.verify(historyQuery).list(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                workbench.getRepositoryScope().getScopeHash(), request);
    }

    @Test
    void should_HideMissingExactDetail_When_RunWasAuthorized() {
        AuthorizedWorkbenchRun authorized = authorize(
                WorkbenchStageRunTestFixtures.runningRun());
        when(historyQuery.findDetail(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                authorized.getWorkbench().getRepositoryScope().getScopeHash(),
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER))
                .thenReturn(Optional.empty());

        assertThrows(WorkbenchRunNotFoundException.class,
                () -> service.detail(
                        WorkbenchStageRunTestFixtures.OWNER,
                        WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                        WorkbenchStageRunTestFixtures.RUN_IDENTIFIER));
    }

    @Test
    void should_ReuseStageSseEnvelope_When_ReadingEventPage() {
        // Given
        AuthorizedWorkbenchRun authorized = authorize(
                WorkbenchStageRunTestFixtures.runningRun());
        ChatRun run = authorized.getRun();
        run.allocateEventSequence(
                2, WorkbenchStageRunTestFixtures.NOW.plusSeconds(4));
        ChatRunEvent first = event(
                run.getId(), 1L, "agent_chunk",
                "{\"content\":\"hello\"}");
        when(eventStore.findEarliestSequence(run.getId())).thenReturn(1L);
        when(eventStore.findAfterThrough(
                run.getId(), 0L, 2L, 1))
                .thenReturn(Collections.singletonList(first));

        // When
        WorkbenchRunEventPage page = service.events(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                new WorkbenchRunEventPageRequest(0L, 1));

        // Then
        assertEquals(1, page.getEvents().size());
        assertEquals(1L, page.getThrough());
        assertEquals(2L, page.getLastEventSeq());
        assertTrue(page.isHasMore());
        String payload = page.getEvents().get(0).getPayload();
        assertTrue(payload.contains(
                "\"stageInstanceIdentifier\":\""
                        + WorkbenchStageRunTestFixtures
                        .STAGE_INSTANCE_IDENTIFIER + "\""));
        assertFalse(payload.contains("\"phase\""));
    }

    @Test
    void should_FailBeforeReadingEvents_When_CursorExpired() {
        AuthorizedWorkbenchRun authorized = authorize(
                WorkbenchStageRunTestFixtures.runningRun());
        authorized.getRun().allocateEventSequence(
                10, WorkbenchStageRunTestFixtures.NOW.plusSeconds(4));
        when(eventStore.findEarliestSequence(authorized.getRun().getId()))
                .thenReturn(5L);

        assertThrows(WorkbenchRunCursorExpiredException.class,
                () -> service.events(
                        WorkbenchStageRunTestFixtures.OWNER,
                        WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                        WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                        new WorkbenchRunEventPageRequest(2L, 50)));

        verifyNoInteractions(historyQuery);
    }

    @Test
    void should_ExposeFrozenStageCapabilitiesWithoutPhaseOverride_When_Queried() {
        // Given
        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                "policy-1", "solution-design", "1", repeat('a'),
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
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withCapabilityBinding(binding);
        AuthorizedWorkbenchRun authorized = AuthorizedWorkbenchRun.verified(
                fixture.workbench(), fixture.snapshot(),
                WorkbenchStageRunTestFixtures.runningRun());
        when(accessResolver.requireAuthorized(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER))
                .thenReturn(authorized);

        // When
        WorkbenchRunCapabilityView view = service.capability(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER);

        // Then
        assertEquals(WorkbenchStageRunTestFixtures.STAGE_INSTANCE_IDENTIFIER,
                view.getStageInstanceIdentifier());
        assertEquals(binding.getBindingHash(), view.getBindingHash());
        assertEquals(1, view.getRules().size());
        assertEquals("platform/safety", view.getRules().get(0).getId());
        assertEquals("安全规则", view.getRules().get(0).getSafeSummary());
        assertEquals("code-search", view.getSkills().get(0).getId());
        assertEquals("repository-query", view.getMcpServers().get(0).getId());
        assertEquals("READ", view.getMcpServers().get(0).getAccess());
        assertEquals("production-write", view.getRejected().get(0).getId());
        assertEquals(authorized.getSnapshot().getRepositoryScopeHash(),
                view.getRepositoryScopeHash());
        assertEquals("agent-web", view.getPrimaryRepositoryKey());
    }

    private AuthorizedWorkbenchRun authorize(ChatRun run) {
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withoutUpload();
        AuthorizedWorkbenchRun authorized = AuthorizedWorkbenchRun.verified(
                fixture.workbench(), fixture.snapshot(), run);
        when(accessResolver.requireAuthorized(
                WorkbenchStageRunTestFixtures.OWNER,
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER))
                .thenReturn(authorized);
        return authorized;
    }

    private ChatRunEvent event(
            ChatRunId runId, long sequence, String eventType,
            String payload) {
        return new ChatRunEvent(
                runId, sequence, eventType, payload,
                payload.getBytes(StandardCharsets.UTF_8).length,
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(sequence));
    }

    private String repeat(char value) {
        char[] characters = new char[64];
        Arrays.fill(characters, value);
        return new String(characters);
    }
}
