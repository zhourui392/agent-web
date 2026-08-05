package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.ChatRunEventDraft;
import com.example.agentweb.app.chatrun.ChatRunTerminalFinalizer;
import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunCancellationDecision;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/**
 * 复用 ChatRun 取消聚合行为的 Workbench 中性停止协调器。
 *
 * <p>Owner 与 Admin 分别在各自授权边界完成 exact binding 后调用；本类不接收、构造或解释 Owner 身份。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public final class WorkbenchRunCancellationCoordinator {

    private final ChatRunEventAppender eventAppender;
    private final ChatRunTerminalFinalizer terminalFinalizer;
    private final ChatRunRuntimeHandleStore handleStore;
    private final AgentExecutionGateway executionGateway;
    private final SafeWorkbenchRunLogger safeLogger;
    private final Clock clock;

    public WorkbenchRunCancellationCoordinator(
            ChatRunEventAppender eventAppender,
            ChatRunTerminalFinalizer terminalFinalizer,
            ChatRunRuntimeHandleStore handleStore,
            AgentExecutionGateway executionGateway,
            SafeWorkbenchRunLogger safeLogger, Clock clock) {
        this.eventAppender = Objects.requireNonNull(
                eventAppender, "eventAppender");
        this.terminalFinalizer = Objects.requireNonNull(
                terminalFinalizer, "terminalFinalizer");
        this.handleStore = Objects.requireNonNull(
                handleStore, "handleStore");
        this.executionGateway = Objects.requireNonNull(
                executionGateway, "executionGateway");
        this.safeLogger = Objects.requireNonNull(
                safeLogger, "safeLogger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WorkbenchRunCancellationResult cancel(
            WorkbenchStageRunSnapshot snapshot, ChatRun run) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(run, "run");
        Instant now = clock.instant();
        ChatRunCancellationDecision decision =
                run.requestCancellation(now);
        if (decision.isTerminalTransition()) {
            terminalFinalizer.finalizeFirstTerminal(run, now);
        } else if (decision.isChanged()) {
            eventAppender.appendToExistingRun(
                    run,
                    Collections.singletonList(new ChatRunEventDraft(
                            "run_status",
                            WorkbenchRunEventPayloadFactory.status(
                                    snapshot, run.getStatus(), now))),
                    now);
            scheduleRuntimeStop(run.getId(), decision);
        }
        return new WorkbenchRunCancellationResult(
                decision, WorkbenchRunStopResult.from(run));
    }

    private void scheduleRuntimeStop(
            final ChatRunId runId,
            ChatRunCancellationDecision decision) {
        if (!decision.isProcessStopRequired()) {
            return;
        }
        eventAppender.afterCommit(new Runnable() {
            @Override
            public void run() {
                requestPersistedRuntimeStop(runId);
            }
        });
    }

    private void requestPersistedRuntimeStop(ChatRunId runId) {
        try {
            Optional<RuntimeHandle> persisted = handleStore.find(runId);
            if (persisted.isPresent()) {
                executionGateway.requestStop(persisted.get());
            }
        } catch (RuntimeException failure) {
            safeLogger.runtimeStopFailed(
                    runId.getValue(),
                    failure.getClass().getSimpleName());
        }
    }
}
