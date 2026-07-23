package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.app.StreamChunkHandler;
import com.example.agentweb.app.refinery.RecallObservationRecorder;
import com.example.agentweb.app.refinery.RecallObservationStart;
import com.example.agentweb.app.refinery.RecallOutcome;
import com.example.agentweb.app.refinery.RecallStatus;
import com.example.agentweb.app.refinery.RecallTrace;
import com.example.agentweb.app.refinery.RefineryRecaller;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.IllegalChatRunTransitionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one persisted ChatRun independently from any browser connection.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
@Slf4j
public class ChatRunExecutor implements ChatRunLauncher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Executor executor;
    private final ChatRunQueryService queryService;
    private final ChatRunLifecycleService lifecycleService;
    private final AgentGateway gateway;
    private final SessionRepository sessionRepository;
    private final ChatRunPromptBuilder promptBuilder;
    private final ChatRunEventBufferFactory eventBufferFactory;
    private final Optional<RefineryRecaller> recaller;
    private final Optional<RecallObservationRecorder> recallRecorder;

    public ChatRunExecutor(@Qualifier("agentExecutor") Executor executor,
                           ChatRunQueryService queryService,
                           ChatRunLifecycleService lifecycleService,
                           AgentGateway gateway,
                           SessionRepository sessionRepository,
                           ChatRunPromptBuilder promptBuilder,
                           ChatRunEventBufferFactory eventBufferFactory,
                           Optional<RefineryRecaller> recaller,
                           Optional<RecallObservationRecorder> recallRecorder) {
        this.executor = executor;
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
        this.gateway = gateway;
        this.sessionRepository = sessionRepository;
        this.promptBuilder = promptBuilder;
        this.eventBufferFactory = eventBufferFactory;
        this.recaller = recaller;
        this.recallRecorder = recallRecorder;
    }

    @Override
    public void launch(final ChatRunId runId) {
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    execute(runId);
                }
            });
        } catch (RuntimeException ex) {
            log.error("chat-run-launch-rejected runId={}", runId.getValue(), ex);
            safeFail(runId, "RUN_LAUNCH_REJECTED", "任务排队失败，请稍后重试");
        }
    }

    private void execute(ChatRunId runId) {
        Optional<ChatRunExecutionContext> found = queryService.findExecutionContext(runId.getValue());
        if (!found.isPresent()) {
            lifecycleService.fail(runId, "RUN_INPUT_MISSING",
                    "任务输入不存在，无法启动", null);
            return;
        }
        ChatRunExecutionContext context = found.get();
        try {
            lifecycleService.start(runId);
        } catch (IllegalChatRunTransitionException ex) {
            log.info("chat-run-start-skipped runId={} reason={}", runId.getValue(), ex.getMessage());
            return;
        }

        final StreamChunkHandler handler = new StreamChunkHandler(sessionRepository,
                context.getSessionId(), gateway, context.getAgentType());
        final AtomicReference<AgentStreamResult> streamResult =
                new AtomicReference<AgentStreamResult>(AgentStreamResult.completed(-1));
        final ChatRunEventBuffer eventBuffer = eventBufferFactory.open(runId,
                error -> gateway.stopStream(runId.getValue()));
        Optional<String> attemptId = createRecallAttempt(context);
        String recallJson = null;
        try {
            RecallOutcome recall = recall(context, attemptId);
            String promptInput = recall.getMessage();
            if (recall.isRecalled()) {
                recallJson = recallJson(recall);
                eventBuffer.append("recall", recallJson);
            }
            String prompt = promptBuilder.prepare(context, promptInput);
            final String finalRecallJson = recallJson;
            gateway.runStreamWithResult(context.getAgentType(), context.getWorkingDir(), prompt,
                    runId.getValue(), context.getResumeId(), context.getEnv(), 0L,
                    handler.onChunk(chunk -> eventBuffer.append("chunk", chunk)),
                    streamResult::set, context.getUserId(), null);
            eventBuffer.flush();
            eventBuffer.close();
            Long assistantMessageId = lifecycleService.complete(runId,
                    handler.accumulatedResponse(), streamResult.get(), finalRecallJson);
            attachAssistant(attemptId, assistantMessageId);
        } catch (Exception ex) {
            log.error("chat-run-execution-failed runId={} sessionId={}",
                    runId.getValue(), context.getSessionId(), ex);
            gateway.stopStream(runId.getValue());
            closeBuffer(eventBuffer);
            if (eventBuffer.getFailure() != null) {
                safeEventStoreFail(runId, streamResult.get().getExitCode());
            } else {
                safeFail(runId);
            }
        }
    }

    private void closeBuffer(ChatRunEventBuffer eventBuffer) {
        try {
            eventBuffer.close();
        } catch (RuntimeException ex) {
            log.warn("chat-run-event-buffer-close-failed reason={}", ex.getMessage());
        }
    }

    private RecallOutcome recall(ChatRunExecutionContext context, Optional<String> attemptId) {
        if (!context.isRecallEnabled() || !recaller.isPresent()) {
            return RecallOutcome.notRecalled(context.getMessage());
        }
        RecallTrace trace = recaller.get().traceForChat(context.getMessage(), context.getWorkingDir());
        if (attemptId.isPresent() && recallRecorder.isPresent()) {
            try {
                recallRecorder.get().tryRecordTrace(attemptId.get(), trace);
            } catch (RuntimeException ex) {
                log.warn("chat-run-recall-trace-record-failed attemptId={} reason={}",
                        attemptId.get(), ex.getMessage());
            }
        }
        return trace.toOutcome();
    }

    private Optional<String> createRecallAttempt(ChatRunExecutionContext context) {
        if (!recallRecorder.isPresent()) {
            return Optional.empty();
        }
        RecallStatus status = RecallStatus.PENDING;
        String skipReason = null;
        if (!context.isRecallEnabled()) {
            status = RecallStatus.SKIPPED;
            skipReason = "DISABLED_BY_CLIENT";
        } else if (!recaller.isPresent()) {
            status = RecallStatus.SKIPPED;
            skipReason = "REFINERY_UNAVAILABLE";
        }
        try {
            return recallRecorder.get().tryCreateStart(new RecallObservationStart(
                    context.getSessionId(), context.getUserMessageId(), context.getMessage(),
                    context.isRecallEnabled(), context.getEnv(), status, skipReason));
        } catch (RuntimeException ex) {
            log.warn("chat-run-recall-attempt-create-failed sessionId={} userMessageId={} reason={}",
                    context.getSessionId(), context.getUserMessageId(), ex.getMessage());
            return Optional.empty();
        }
    }

    private void attachAssistant(Optional<String> attemptId, Long assistantMessageId) {
        if (attemptId.isPresent() && assistantMessageId != null && recallRecorder.isPresent()) {
            try {
                recallRecorder.get().tryAttachAssistantMessage(
                        attemptId.get(), assistantMessageId.longValue());
            } catch (RuntimeException ex) {
                log.warn("chat-run-recall-assistant-attach-failed attemptId={} messageId={} reason={}",
                        attemptId.get(), assistantMessageId, ex.getMessage());
            }
        }
    }

    private String recallJson(RecallOutcome recall) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("query", recall.getQuery());
        payload.put("status", "HIT");
        payload.put("hits", recall.getHits());
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not serialize recall event", ex);
        }
    }

    private void safeFail(ChatRunId runId) {
        safeFail(runId, "EXECUTION_FAILED", "Agent 执行失败，请稍后重试");
    }

    private void safeFail(ChatRunId runId, String failureCode, String publicMessage) {
        try {
            lifecycleService.fail(runId, failureCode, publicMessage, null);
        } catch (RuntimeException finishFailure) {
            log.error("chat-run-failure-finalize-failed runId={}", runId.getValue(), finishFailure);
        }
    }

    private void safeEventStoreFail(ChatRunId runId, Integer exitCode) {
        try {
            lifecycleService.fail(runId, "EVENT_STORE_WRITE_FAILED",
                    "流事件保存失败，任务已停止", exitCode);
        } catch (RuntimeException finishFailure) {
            log.error("chat-run-event-failure-finalize-failed runId={}",
                    runId.getValue(), finishFailure);
        }
    }
}
