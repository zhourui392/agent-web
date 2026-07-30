package com.example.agentweb.infra.agentrun;

import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.app.agentrun.port.AgentRunInvocation;
import com.example.agentweb.app.agentrun.port.AgentRuntime;
import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.app.agentrun.port.HistoryDeliveryMode;
import com.example.agentweb.domain.agentrun.AgentRuntimeUnavailableException;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentCliGateway;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Routes provider-neutral invocations while preserving run-scoped stop semantics.
 *
 * @author alex
 * @since 2026-07-29
 */
@Component
@Primary
public class RoutingAgentGateway implements AgentGateway {

    private static final int TERMINAL_RUN_CACHE_LIMIT = 4096;

    private final AgentCliGateway cli;
    private final Map<AgentType, AgentRuntime> runtimes;
    private final Map<String, AgentRuntime> active = new ConcurrentHashMap<String, AgentRuntime>();
    private final Set<String> pendingCancellation = ConcurrentHashMap.newKeySet();
    private final Set<String> terminalRuns = ConcurrentHashMap.newKeySet();
    private final Queue<String> terminalRunOrder = new ConcurrentLinkedQueue<String>();

    public RoutingAgentGateway(AgentCliGateway cli, List<AgentRuntime> runtimeBeans) {
        this.cli = cli;
        this.runtimes = index(runtimeBeans);
    }

    @Override
    public String runOnce(AgentType type, String workingDir, String userMessage, String userId)
            throws IOException, InterruptedException {
        requireOneShotSupported(type);
        return cli.runOnce(type, workingDir, userMessage, userId);
    }

    @Override
    public void requireOneShotSupported(AgentType type) {
        requireCli(type);
    }

    @Override
    public void runStream(AgentType type, String workingDir, String userMessage,
                          String sessionId, String resumeId, String env, long timeoutSeconds,
                          Consumer<String> onChunk, IntConsumer onExit, String userId,
                          Map<String, String> extraEnv) throws IOException, InterruptedException {
        runStreamWithResult(invocation(type, workingDir, userMessage, sessionId, resumeId,
                env, timeoutSeconds, userId, extraEnv), onChunk,
                result -> onExit.accept(result.getStreamResult().getExitCode()));
    }

    @Override
    public void runStreamWithResult(AgentType type, String workingDir, String userMessage,
                                    String sessionId, String resumeId, String env,
                                    long timeoutSeconds, Consumer<String> onChunk,
                                    Consumer<AgentStreamResult> onExit, String userId,
                                    Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        runStreamWithResult(invocation(type, workingDir, userMessage, sessionId, resumeId,
                env, timeoutSeconds, userId, extraEnv), onChunk,
                result -> onExit.accept(result.getStreamResult()));
    }

    @Override
    public void runStreamWithResult(AgentRunInvocation invocation, Consumer<String> onChunk,
                                    Consumer<AgentExecutionResult> onComplete)
            throws IOException, InterruptedException {
        AgentRuntime runtime = requireRuntime(invocation.getAgentType());
        register(invocation.getRunId(), runtime);
        AtomicBoolean terminal = new AtomicBoolean(false);
        try {
            if (pendingCancellation.remove(invocation.getRunId())) {
                completeOnce(terminal, onComplete, AgentExecutionResult.stopped());
                return;
            }
            runtime.run(invocation, chunk -> {
                        if (!terminal.get()) {
                            onChunk.accept(chunk);
                        }
                    },
                    result -> completeOnce(terminal, onComplete, result));
        } finally {
            markTerminal(invocation.getRunId());
            active.remove(invocation.getRunId(), runtime);
            pendingCancellation.remove(invocation.getRunId());
        }
    }

    @Override
    public void stopStream(String runId) {
        AgentRuntime runtime = active.get(runId);
        if (runtime != null) {
            pendingCancellation.add(runId);
            runtime.stop(runId);
            return;
        }
        if (!terminalRuns.contains(runId)) {
            pendingCancellation.add(runId);
        }
    }

    @Override
    public String extractResumeId(AgentType type, String stdoutLine) {
        return requireRuntime(type).extractResumeId(type, stdoutLine);
    }

    @Override
    public List<String> normalizeChunk(AgentType type, String stdoutLine) {
        return requireRuntime(type).normalizeChunk(type, stdoutLine);
    }

    @Override
    public HistoryDeliveryMode historyDeliveryMode(AgentType type) {
        return requireRuntime(type).historyDeliveryMode();
    }

    private Map<AgentType, AgentRuntime> index(List<AgentRuntime> beans) {
        EnumMap<AgentType, AgentRuntime> result = new EnumMap<AgentType, AgentRuntime>(AgentType.class);
        for (AgentRuntime runtime : beans) {
            for (AgentType type : runtime.supportedTypes()) {
                if (result.put(type, runtime) != null) {
                    throw new IllegalStateException("Duplicate agent runtime: " + type);
                }
            }
        }
        return result;
    }

    private AgentRuntime requireRuntime(AgentType type) {
        AgentRuntime runtime = runtimes.get(type);
        if (runtime == null) {
            throw new AgentRuntimeUnavailableException(
                    "AGENT_RUNTIME_UNAVAILABLE", "Agent runtime is unavailable: " + type);
        }
        return runtime;
    }

    private void requireCli(AgentType type) {
        if (type == AgentType.NATIVE) {
            throw new AgentRuntimeUnavailableException(
                    "AGENT_SURFACE_UNAVAILABLE", "NATIVE only supports ChatRun streaming");
        }
    }

    private void register(String runId, AgentRuntime runtime) {
        if (active.putIfAbsent(runId, runtime) != null) {
            throw new IllegalStateException("Agent run already registered: " + runId);
        }
    }

    private void completeOnce(AtomicBoolean terminal, Consumer<AgentExecutionResult> completion,
                              AgentExecutionResult result) {
        if (terminal.compareAndSet(false, true)) {
            completion.accept(result);
        }
    }

    private void markTerminal(String runId) {
        if (!terminalRuns.add(runId)) {
            return;
        }
        terminalRunOrder.add(runId);
        while (terminalRuns.size() > TERMINAL_RUN_CACHE_LIMIT) {
            String expired = terminalRunOrder.poll();
            if (expired == null) {
                return;
            }
            terminalRuns.remove(expired);
        }
    }

    private AgentRunInvocation invocation(AgentType type, String workingDir, String prompt,
                                          String runId, String resumeId, String env,
                                          long timeoutSeconds, String userId,
                                          Map<String, String> extraEnv) {
        return AgentRunInvocation.builder()
                .runId(runId)
                .conversationId(runId)
                .userMessageId(-1L)
                .agentType(type)
                .workingDir(workingDir)
                .prompt(prompt)
                .resumeId(resumeId)
                .env(env)
                .userId(userId)
                .timeoutSeconds(timeoutSeconds)
                .extraEnv(extraEnv)
                .build();
    }
}
