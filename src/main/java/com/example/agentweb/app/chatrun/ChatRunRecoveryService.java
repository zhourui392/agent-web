package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.runtime.port.RuntimeState;
import com.example.agentweb.app.runtime.port.RuntimeTermination;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.chatrun.ChatRunRecoveryDecision;
import com.example.agentweb.domain.chatrun.ChatRunRecoveryLiveness;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Marks database-active runs as interrupted when the current JVM cannot own their process.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Service
@Slf4j
public class ChatRunRecoveryService {

    private static final String RESTART_MESSAGE = "服务重启，任务已中断";

    private final ChatRunQueryService queryService;
    private final ChatRunRepository runRepository;
    private final ChatRunLifecycleService lifecycleService;
    private final ChatRunRuntimeHandleStore handleStore;
    private final AgentExecutionGateway executionGateway;
    private final ChatRunRuntimeTerminationReconciler terminationReconciler;
    private final List<ChatRunRecoveryObserver> recoveryObservers;

    public ChatRunRecoveryService(ChatRunQueryService queryService,
                                  ChatRunRepository runRepository,
                                  ChatRunLifecycleService lifecycleService,
                                  ChatRunRuntimeHandleStore handleStore,
                                  AgentExecutionGateway executionGateway,
                                  ChatRunRuntimeTerminationReconciler
                                          terminationReconciler,
                                  List<ChatRunRecoveryObserver>
                                          recoveryObservers) {
        this.queryService = Objects.requireNonNull(
                queryService, "queryService");
        this.runRepository = Objects.requireNonNull(
                runRepository, "runRepository");
        this.lifecycleService = Objects.requireNonNull(
                lifecycleService, "lifecycleService");
        this.handleStore = Objects.requireNonNull(
                handleStore, "handleStore");
        this.executionGateway = Objects.requireNonNull(
                executionGateway, "executionGateway");
        this.terminationReconciler = Objects.requireNonNull(
                terminationReconciler, "terminationReconciler");
        if (recoveryObservers == null || recoveryObservers.contains(null)) {
            throw new IllegalArgumentException(
                    "chat run recovery observers must not contain null");
        }
        this.recoveryObservers = Collections.unmodifiableList(
                new ArrayList<ChatRunRecoveryObserver>(recoveryObservers));
    }

    public int interruptOrphans() {
        int recovered = 0;
        for (String runIdValue : queryService.findActiveRunIds()) {
            try {
                recovered += reconcileScanned(ChatRunId.of(runIdValue));
            } catch (RuntimeException failure) {
                logFailure("chat-run-recovery-failed", runIdValue, failure);
            }
        }
        return recovered;
    }

    /**
     * 对一个已明确选择的 Run 执行恢复对账，不扫描或重放其他运行。
     *
     * @param runId 精确 Run ID
     * @return ChatRun 聚合作出的恢复决议
     */
    public ChatRunRecoveryDecision reconcileOne(ChatRunId runId) {
        Objects.requireNonNull(runId, "runId");
        ChatRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ChatRunNotFoundException(
                        runId.getValue()));
        return reconcileAndNotify(run);
    }

    private int reconcileScanned(ChatRunId runId) {
        Optional<ChatRun> found = runRepository.findById(runId);
        if (!found.isPresent()) {
            return 0;
        }
        ChatRunRecoveryDecision decision = reconcileAndNotify(found.get());
        return decision.isRecoveryApplied() ? 1 : 0;
    }

    private ChatRunRecoveryDecision reconcileAndNotify(ChatRun run) {
        try {
            return reconcile(run);
        } catch (RuntimeException failure) {
            notifyFailed(run);
            throw failure;
        }
    }

    private ChatRunRecoveryDecision reconcile(ChatRun run) {
        ChatRunId runId = run.getId();
        Optional<RuntimeHandle> persistedHandle;
        try {
            persistedHandle = handleStore.find(runId);
        } catch (RuntimeException failure) {
            logFailure("chat-run-recovery-handle-read-failed",
                    runId.getValue(), failure);
            return apply(run, ChatRunRecoveryLiveness.UNAVAILABLE,
                    null, null, false, false);
        }
        if (!persistedHandle.isPresent()) {
            return apply(run, ChatRunRecoveryLiveness.UNAVAILABLE,
                    null, null, false, false);
        }
        RuntimeHandle handle = persistedHandle.get();
        RuntimeObservation observation;
        try {
            observation = executionGateway.observe(handle);
        } catch (RuntimeException failure) {
            logFailure("chat-run-recovery-observe-failed",
                    runId.getValue(), failure);
            return apply(run, ChatRunRecoveryLiveness.UNAVAILABLE,
                    handle, null, true, false);
        }
        return applyObservation(run, handle, observation);
    }

    private ChatRunRecoveryDecision applyObservation(
            ChatRun run, RuntimeHandle handle,
            RuntimeObservation observation) {
        RuntimeState state = observation.getState();
        if (state == RuntimeState.RUNNING
                || state == RuntimeState.STOP_REQUESTED) {
            return apply(run, ChatRunRecoveryLiveness.ALIVE,
                    handle, null, false, false);
        }
        if (state == RuntimeState.TERMINATED) {
            RuntimeTermination termination = observation.termination()
                    .orElseThrow(() -> new IllegalStateException(
                            "terminated runtime observation has no terminal fact"));
            return apply(run, ChatRunRecoveryLiveness.TERMINATED,
                    handle, termination, false, true);
        }
        return apply(run, ChatRunRecoveryLiveness.UNAVAILABLE,
                handle, null, false, true);
    }

    private ChatRunRecoveryDecision apply(
            ChatRun run, ChatRunRecoveryLiveness liveness,
            RuntimeHandle handle, RuntimeTermination termination,
            boolean stopUnknownHandle, boolean removableHandle) {
        ChatRunRecoveryDecision decision = run.decideRecovery(liveness);
        switch (decision) {
            case RETAIN_ACTIVE:
                break;
            case FINALIZE_TERMINATION:
                terminationReconciler.reconcile(
                        run.getId(), requireHandle(handle),
                        requireTermination(termination));
                handleStore.delete(run.getId());
                break;
            case STOP_AND_INTERRUPT:
                requestStopSafely(run.getId(), requireHandle(handle));
                lifecycleService.interrupt(run.getId(), RESTART_MESSAGE);
                break;
            case INTERRUPT:
                if (stopUnknownHandle && handle != null) {
                    requestStopSafely(run.getId(), handle);
                }
                lifecycleService.interrupt(run.getId(), RESTART_MESSAGE);
                if (removableHandle && handle != null) {
                    handleStore.delete(run.getId());
                }
                break;
            case IGNORE_TERMINAL:
                if (removableHandle && handle != null) {
                    handleStore.delete(run.getId());
                }
                break;
            default:
                throw new IllegalStateException(
                        "unsupported chat run recovery decision");
        }
        notifyReconciled(run, decision);
        return decision;
    }

    private void requestStopSafely(ChatRunId runId, RuntimeHandle handle) {
        try {
            executionGateway.requestStop(handle);
        } catch (RuntimeException failure) {
            logFailure("chat-run-recovery-stop-failed",
                    runId.getValue(), failure);
        }
    }

    private RuntimeHandle requireHandle(RuntimeHandle handle) {
        return Objects.requireNonNull(handle,
                "runtime handle is required for recovery decision");
    }

    private RuntimeTermination requireTermination(
            RuntimeTermination termination) {
        return Objects.requireNonNull(termination,
                "runtime termination is required for recovery decision");
    }

    private void logFailure(
            String action, String runId, RuntimeException failure) {
        log.error("{} runId={} failureType={}", action, runId,
                failure.getClass().getSimpleName());
    }

    private void notifyReconciled(
            ChatRun run, ChatRunRecoveryDecision decision) {
        for (ChatRunRecoveryObserver observer : recoveryObservers) {
            try {
                observer.reconciled(run, decision);
            } catch (RuntimeException failure) {
                logFailure("chat-run-recovery-observer-failed",
                        run.getId().getValue(), failure);
            }
        }
    }

    private void notifyFailed(ChatRun run) {
        for (ChatRunRecoveryObserver observer : recoveryObservers) {
            try {
                observer.failed(run);
            } catch (RuntimeException failure) {
                logFailure("chat-run-recovery-observer-failed",
                        run.getId().getValue(), failure);
            }
        }
    }
}
