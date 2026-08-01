package com.example.agentweb.app.runtime;

import com.example.agentweb.app.chatrun.ChatRunExecutionContext;
import com.example.agentweb.app.chatrun.ChatRunPromptBuilder;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.PreparedChatRunPrompt;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.CredentialReference;
import com.example.agentweb.app.runtime.port.HistoryDelivery;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 普通 Chat 已持久化执行事实到公共 Runtime Plan 的应用编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ChatExecutionPlanProviderTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    private ChatRunQueryService queryService;
    private ChatRunPromptBuilder promptBuilder;
    private ResolvedCapabilityBinding capabilityBinding;
    private RuntimeLimits runtimeLimits;
    private CredentialReference credentialReference;
    private ChatExecutionPlanProvider provider;

    @BeforeEach
    void setUp() {
        queryService = mock(ChatRunQueryService.class);
        promptBuilder = mock(ChatRunPromptBuilder.class);
        capabilityBinding = binding();
        runtimeLimits = new RuntimeLimits(
                Duration.ofMinutes(30), 8_388_608L,
                Collections.<String>emptySet());
        credentialReference = CredentialReference.environment(
                "AGENT_COMMON_RUNTIME_API_KEY");
        provider = new ChatExecutionPlanProvider(
                queryService, promptBuilder, capabilityBinding,
                runtimeLimits, credentialReference);
    }

    @Test
    void shouldSupportOnlyChatOrigin() {
        assertTrue(provider.supports(RunOrigin.CHAT));
        assertEquals(false, provider.supports(RunOrigin.WORKBENCH));
    }

    @Test
    void shouldAssembleCompletePlanFromPersistedChatExecutionContext() {
        ChatRun run = chatRun(false);
        ChatRunExecutionContext context = context(
                AgentType.CODEX, null, false);
        when(queryService.findExecutionContext("chat-run-1"))
                .thenReturn(Optional.of(context));
        when(promptBuilder.prepareDetailed(context, "question"))
                .thenReturn(new PreparedChatRunPrompt(
                        "assembled prompt", null));

        AgentExecutionPlan plan = provider.prepare(run);

        assertEquals("chat-run-1",
                plan.getExecutionIdentity().getExecutionId());
        assertEquals("user-1", plan.getExecutionIdentity().getOwnerId());
        assertEquals("chat:session-1",
                plan.getExecutionIdentity().getOriginReference());
        assertEquals(AgentType.CODEX,
                plan.getRuntimeSelection().getAgentType());
        assertEquals("assembled prompt",
                plan.getPromptPayload().getFinalPrompt());
        assertEquals(CanonicalHashing.sha256("assembled prompt"),
                plan.getPromptPayload().getPromptHash());
        assertEquals(HistoryDelivery.PROMPT_PREFIX,
                plan.getPromptPayload().getHistoryDelivery());
        assertEquals("/workspace/agent-web",
                plan.getWorkspaceLayout().getPrimaryRepositoryRoot());
        assertEquals(Collections.singletonList("/workspace/agent-web"),
                plan.getWorkspaceLayout().getReadableRoots());
        assertEquals(Collections.singletonList("/workspace/agent-web"),
                plan.getWorkspaceLayout().getWritableRoots());
        assertEquals(SandboxMode.WORKSPACE_WRITE,
                plan.getWorkspaceLayout().getSandboxMode());
        assertSame(capabilityBinding, plan.getCapabilityBinding());
        assertSame(runtimeLimits, plan.getRuntimeLimits());
        assertSame(credentialReference,
                plan.getRuntimeSelection().getCredentialReference());
    }

    @Test
    void missingPersistedContextShouldFailClosed() {
        ChatRun run = chatRun(false);
        when(queryService.findExecutionContext("chat-run-1"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> provider.prepare(run));

        verifyNoInteractions(promptBuilder);
    }

    @Test
    void unsupportedAgentRecallAndResumeShouldFailBeforePromptAssembly() {
        ChatRun run = chatRun(false);
        when(queryService.findExecutionContext("chat-run-1"))
                .thenReturn(Optional.of(context(
                        AgentType.CLAUDE, null, false)));
        assertThrows(IllegalStateException.class,
                () -> provider.prepare(run));

        when(queryService.findExecutionContext("chat-run-1"))
                .thenReturn(Optional.of(context(
                        AgentType.CODEX, null, true)));
        assertThrows(IllegalStateException.class,
                () -> provider.prepare(run));

        when(queryService.findExecutionContext("chat-run-1"))
                .thenReturn(Optional.of(context(
                        AgentType.CODEX, "resume-1", false)));
        assertThrows(IllegalStateException.class,
                () -> provider.prepare(run));

        verifyNoInteractions(promptBuilder);
    }

    @Test
    void persistedRecallEnabledRunShouldFailClosedEvenIfProjectionIsCorrupted() {
        ChatRun run = chatRun(true);
        when(queryService.findExecutionContext("chat-run-1"))
                .thenReturn(Optional.of(context(
                        AgentType.CODEX, null, false)));

        assertThrows(IllegalStateException.class,
                () -> provider.prepare(run));

        verifyNoInteractions(promptBuilder);
    }

    @Test
    void workbenchRunShouldBeRejectedBeforeReadingChatProjection() {
        ChatRun run = ChatRun.submit(
                ChatRunId.of("workbench-run-1"), "session-1", 1L,
                "submit-workbench", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:IMPLEMENT_TEST", "workbench-run-1"), NOW);

        assertThrows(RuntimeException.class,
                () -> provider.prepare(run));

        verifyNoInteractions(queryService, promptBuilder);
    }

    private ChatRun chatRun(boolean recallEnabled) {
        return ChatRun.submit(
                ChatRunId.of("chat-run-1"), "session-1", 11L,
                "submit-chat", recallEnabled, NOW);
    }

    private ChatRunExecutionContext context(
            AgentType agentType, String resumeId, boolean recallEnabled) {
        return new ChatRunExecutionContext(
                "chat-run-1", "session-1", 11L, agentType,
                "/workspace/agent-web", resumeId, "local", "user-1",
                "question", recallEnabled, Collections.emptyList());
    }

    private ResolvedCapabilityBinding binding() {
        return ResolvedCapabilityBinding.resolve(
                "policy@1", "chat-default", "1",
                CanonicalHashing.sha256("chat-default@1"),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                "common-runtime@1");
    }
}
