package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpoint;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpointRepository;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunCompletionDecision;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Short transactional lifecycle operations used by the long-running CLI executor.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Service
public class ChatRunLifecycleService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatRunRepository runRepository;
    private final SessionRepository sessionRepository;
    private final DiagnosisCheckpointRepository checkpointRepository;
    private final ChatRunEventAppender eventAppender;
    private final ChatRunTerminalFinalizer terminalFinalizer;
    private final Clock clock;

    public ChatRunLifecycleService(ChatRunRepository runRepository,
                                   SessionRepository sessionRepository,
                                   DiagnosisCheckpointRepository checkpointRepository,
                                   ChatRunEventAppender eventAppender,
                                   ChatRunTerminalFinalizer terminalFinalizer,
                                   Clock clock) {
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.checkpointRepository = checkpointRepository;
        this.eventAppender = eventAppender;
        this.terminalFinalizer = terminalFinalizer;
        this.clock = clock;
    }

    @Transactional
    public void start(ChatRunId runId) {
        ChatRun run = require(runId);
        Instant now = clock.instant();
        run.start(now);
        appendExisting(run, "run_status", statusPayload(run), now);
    }

    @Transactional
    public void append(ChatRunId runId, String eventType, String payload) {
        appendBatch(runId, Collections.singletonList(new ChatRunEventDraft(eventType, payload)));
    }

    @Transactional
    public void appendBatch(ChatRunId runId, List<ChatRunEventDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("chat run event batch must not be empty");
        }
        ChatRun run = require(runId);
        Instant now = clock.instant();
        eventAppender.appendToExistingRun(run, drafts, now);
    }

    @Transactional
    public Long complete(ChatRunId runId, String normalizedOutput, int exitCode, String recallJson) {
        return complete(runId, normalizedOutput, AgentStreamResult.completed(exitCode), recallJson);
    }

    @Transactional
    public Long complete(ChatRunId runId, String normalizedOutput, AgentStreamResult streamResult,
                         String recallJson) {
        return complete(runId, normalizedOutput,
                AgentExecutionResult.fromStream(streamResult), recallJson);
    }

    @Transactional
    public Long complete(ChatRunId runId, String normalizedOutput, AgentExecutionResult executionResult,
                         String recallJson) {
        ChatRun run = require(runId);
        Instant now = clock.instant();
        String response = normalizedOutput == null ? "" : normalizedOutput.trim();
        AgentExecutionResult execution = Objects.requireNonNull(
                executionResult, "executionResult");
        AgentStreamResult result = execution.getStreamResult();
        int exitCode = result.getExitCode();
        ChatRunCompletionDecision decision = run.decideCompletion(exitCode, !response.isEmpty());
        if (decision == ChatRunCompletionDecision.SUCCEED) {
            long assistantMessageId = sessionRepository.addMessageReturningId(run.getSessionId(),
                    new ChatMessage("assistant", response, now));
            if (recallJson != null) {
                sessionRepository.saveRecall(assistantMessageId, recallJson);
            }
            execution.checkpoint().ifPresent(payload -> checkpointRepository.save(
                    DiagnosisCheckpoint.record(run.getId().getValue(), run.getSessionId(),
                            run.getUserMessageId(), assistantMessageId,
                            payload.stateSnapshot(), payload.schemaVersion(),
                            execution.getUsage().inputTokens(),
                            execution.getUsage().outputTokens(),
                            execution.getUsage().cacheReadInputTokens(), now)));
            run.succeed(assistantMessageId, exitCode, now);
            terminalFinalizer.finalizeFirstTerminal(run, now);
            return Long.valueOf(assistantMessageId);
        }
        if (decision == ChatRunCompletionDecision.CANCEL) {
            run.cancel(exitCode, now);
            terminalFinalizer.finalizeFirstTerminal(run, now);
            return null;
        }
        String failureCode = failureCode(execution);
        String publicMessage = publicFailureMessage(execution);
        run.fail(failureCode, publicMessage, exitCode, now);
        terminalFinalizer.finalizeFirstTerminal(run, now);
        return null;
    }

    private String failureCode(AgentExecutionResult execution) {
        AgentStreamResult.TerminationReason reason =
                execution.getStreamResult().getTerminationReason();
        switch (reason) {
            case IDLE_TIMEOUT:
                return "IDLE_TIMEOUT";
            case MAX_RUNTIME_TIMEOUT:
                return "MAX_RUNTIME_TIMEOUT";
            case HARD_TIMEOUT:
                return "HARD_TIMEOUT";
            case OUTPUT_LIMIT:
                return "OUTPUT_LIMIT";
            case COMPLETED:
                if ("REJECTED".equals(execution.getProviderExitReason())) {
                    return "AGENT_REJECTED";
                }
                if ("ERROR".equals(execution.getProviderExitReason())
                        || "STOPPED".equals(execution.getProviderExitReason())) {
                    return "AGENT_EXECUTION_ERROR";
                }
                return "EXECUTION_FAILED";
            default:
                throw new IllegalArgumentException("unsupported stream termination reason: " + reason);
        }
    }

    private String publicFailureMessage(AgentExecutionResult execution) {
        AgentStreamResult.TerminationReason reason =
                execution.getStreamResult().getTerminationReason();
        switch (reason) {
            case IDLE_TIMEOUT:
                return "Agent 长时间无输出，任务已停止";
            case MAX_RUNTIME_TIMEOUT:
                return "Agent 运行时间超过上限，任务已停止";
            case HARD_TIMEOUT:
                return "Agent 执行超时，任务已停止";
            case OUTPUT_LIMIT:
                return "输出超过上限，任务已停止";
            case COMPLETED:
                if ("REJECTED".equals(execution.getProviderExitReason())) {
                    return "Agent 拒绝执行当前请求，请检查配置或稍后重试";
                }
                return "Agent 执行失败，请稍后重试";
            default:
                throw new IllegalArgumentException("unsupported stream termination reason: " + reason);
        }
    }

    @Transactional
    public void fail(ChatRunId runId, String failureCode, String publicMessage, Integer exitCode) {
        ChatRun run = require(runId);
        Instant now = clock.instant();
        if (run.fail(failureCode, publicMessage, exitCode, now)) {
            terminalFinalizer.finalizeFirstTerminal(run, now);
        }
    }

    @Transactional
    public void interrupt(ChatRunId runId, String publicMessage) {
        ChatRun run = require(runId);
        Instant now = clock.instant();
        if (run.interrupt(publicMessage, now)) {
            terminalFinalizer.finalizeFirstTerminal(run, now);
        }
    }

    private ChatRun require(ChatRunId runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new ChatRunNotFoundException(runId.getValue()));
    }

    private void appendExisting(ChatRun run, String eventType, String payload, Instant now) {
        eventAppender.appendToExistingRun(run, Collections.singletonList(
                new ChatRunEventDraft(eventType, payload)), now);
    }

    private String statusPayload(ChatRun run) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", run.getStatus().name());
        return serialize(payload);
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not serialize chat run event", ex);
        }
    }
}
