package com.example.agentweb.infra.agentrun;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.AgentRuntimeSurface;
import com.example.agentweb.app.runtime.port.ExecutionIdentity;
import com.example.agentweb.app.runtime.port.HistoryDelivery;
import com.example.agentweb.app.runtime.port.PromptPayload;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.config.EnvProperties;
import com.example.agentweb.config.cli.AgentCliProperties;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.infra.cli.CliDialect;
import com.example.agentweb.infra.git.GitProcessEnvCustomizer;
import com.example.agentweb.infra.runtime.AgentProcessKernel;
import com.example.agentweb.infra.runtime.ProcessEnvironmentSanitizer;
import com.example.agentweb.infra.runtime.profile.AgentRuntimeProfile;
import com.example.agentweb.infra.runtime.profile.AgentRuntimeProfileCatalog;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CLI Runtime start 在缺省 Profile 时继承本机登录态。
 *
 * @author alex
 * @since 2026-08-19
 */
class CliAgentRuntimeTest {

    @Test
    void should_StartClaudeWithInheritedCliLogin_When_ProfileIsAbsent() {
        AgentProcessKernel kernel = mock(AgentProcessKernel.class);
        RuntimeHandle handle = new RuntimeHandle("chat-run-1", "handle-1");
        when(kernel.start(any(), any(), any(), any(), isNull())).thenReturn(handle);
        CliDialect dialect = mock(CliDialect.class);
        when(dialect.type()).thenReturn(AgentType.CLAUDE);
        CliAgentRuntime runtime = new CliAgentRuntime(
                new AgentCliProperties(), new EnvProperties(),
                mock(GitProcessEnvCustomizer.class),
                new ProcessEnvironmentSanitizer(), kernel,
                new AgentRuntimeProfileCatalog(List.of(codexProfile())),
                List.of(dialect));

        RuntimeHandle started = runtime.start(claudePlanWithoutProfile(), event -> { });

        assertSame(handle, started);
        verify(kernel).start(any(AgentExecutionPlan.class), any(RuntimeEventSink.class),
                eq(dialect), any(AgentCliProperties.Client.class), isNull());
    }

    private AgentRuntimeProfile codexProfile() {
        return new AgentRuntimeProfile("codex-local", AgentType.CODEX, null, null,
                "gpt-5.6-sol", Set.of("gpt-5.6-sol"), "high", Set.of("high"),
                null, Set.of(AgentRuntimeSurface.CHAT),
                Set.of(RunMode.DISCUSS_READ_ONLY), true);
    }

    private AgentExecutionPlan claudePlanWithoutProfile() {
        String prompt = "hi";
        return new AgentExecutionPlan(
                new ExecutionIdentity("chat-run-1", "admin", "chat:session-1"),
                new RuntimeSelection(AgentType.CLAUDE, RuntimeVersionPolicy.configured()),
                new PromptPayload(prompt, CanonicalHashing.sha256(prompt),
                        HistoryDelivery.PROMPT_PREFIX),
                new WorkspaceLayout("/workspace/agent-web",
                        Collections.singletonList("/workspace/agent-web"),
                        Collections.singletonList("/workspace/agent-web"),
                        SandboxMode.WORKSPACE_WRITE),
                ResolvedCapabilityBinding.resolve(
                        "policy@1", "chat-default", "1",
                        CanonicalHashing.sha256("chat-default@1"),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(),
                        "common-runtime@1"),
                new RuntimeLimits(Duration.ofMinutes(30), 8_388_608L));
    }
}
