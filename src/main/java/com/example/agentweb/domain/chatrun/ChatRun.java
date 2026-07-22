package com.example.agentweb.domain.chatrun;

import lombok.Getter;

import java.time.Instant;

/**
 * Aggregate root for one user-message to agent-result execution lifecycle.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class ChatRun {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final ChatRunId id;
    private final String sessionId;
    private final long userMessageId;
    private final String idempotencyKey;
    private final boolean recallEnabled;
    private final Instant createdAt;
    private ChatRunStatus status;
    private Long assistantMessageId;
    private long lastEventSeq;
    private Integer exitCode;
    private String failureCode;
    private String errorMessage;
    private Instant startedAt;
    private Instant cancelRequestedAt;
    private Instant finishedAt;
    private Instant updatedAt;
    private long version;

    private ChatRun(ChatRunId id, String sessionId, long userMessageId, String idempotencyKey,
                    boolean recallEnabled,
                    ChatRunStatus status, Long assistantMessageId, long lastEventSeq,
                    Integer exitCode, String failureCode, String errorMessage,
                    Instant createdAt, Instant startedAt, Instant cancelRequestedAt,
                    Instant finishedAt, Instant updatedAt, long version) {
        this.id = requireId(id);
        this.sessionId = requireText(sessionId, "session id");
        if (userMessageId <= 0L) {
            throw new IllegalArgumentException("user message id must be positive");
        }
        this.userMessageId = userMessageId;
        this.idempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        this.recallEnabled = recallEnabled;
        this.status = requireStatus(status);
        this.assistantMessageId = assistantMessageId;
        if (lastEventSeq < 0L) {
            throw new IllegalArgumentException("last event sequence must not be negative");
        }
        this.lastEventSeq = lastEventSeq;
        this.exitCode = exitCode;
        this.failureCode = failureCode;
        this.errorMessage = errorMessage;
        this.createdAt = requireTime(createdAt, "created time");
        this.startedAt = startedAt;
        this.cancelRequestedAt = cancelRequestedAt;
        this.finishedAt = finishedAt;
        this.updatedAt = updatedAt == null ? createdAt : updatedAt;
        if (this.updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updated time must not be before created time");
        }
        if (version < 0L) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        validateRestoredState();
    }

    public static ChatRun submit(ChatRunId id, String sessionId, long userMessageId,
                                 String idempotencyKey, Instant now) {
        return submit(id, sessionId, userMessageId, idempotencyKey, true, now);
    }

    public static ChatRun submit(ChatRunId id, String sessionId, long userMessageId,
                                 String idempotencyKey, boolean recallEnabled, Instant now) {
        return new ChatRun(id, sessionId, userMessageId, idempotencyKey, recallEnabled,
                ChatRunStatus.PENDING, null, 0L, null, null, null,
                now, null, null, null, now, 0L);
    }

    public static ChatRun restore(ChatRunId id, String sessionId, long userMessageId,
                                  Long assistantMessageId, String idempotencyKey,
                                  boolean recallEnabled,
                                  ChatRunStatus status, long lastEventSeq, Integer exitCode,
                                  String failureCode, String errorMessage, Instant createdAt,
                                  Instant startedAt, Instant cancelRequestedAt, Instant finishedAt,
                                  Instant updatedAt, long version) {
        return new ChatRun(id, sessionId, userMessageId, idempotencyKey, recallEnabled, status,
                assistantMessageId, lastEventSeq, exitCode, failureCode, errorMessage,
                createdAt, startedAt, cancelRequestedAt, finishedAt, updatedAt, version);
    }

    public boolean start(Instant now) {
        requireTransition(ChatRunStatus.PENDING, ChatRunStatus.RUNNING);
        requireLifecycleTime(now);
        status = ChatRunStatus.RUNNING;
        startedAt = now;
        touch(now);
        return true;
    }

    public ChatRunCancellationDecision requestCancellation(Instant now) {
        requireLifecycleTime(now);
        if (status == ChatRunStatus.CANCEL_REQUESTED || status == ChatRunStatus.CANCELLED) {
            return ChatRunCancellationDecision.UNCHANGED;
        }
        if (status.isTerminal()) {
            return ChatRunCancellationDecision.UNCHANGED;
        }
        if (status == ChatRunStatus.PENDING) {
            status = ChatRunStatus.CANCELLED;
            finishedAt = now;
            touch(now);
            return ChatRunCancellationDecision.CANCELLED_BEFORE_START;
        }
        status = ChatRunStatus.CANCEL_REQUESTED;
        cancelRequestedAt = now;
        touch(now);
        return ChatRunCancellationDecision.REQUESTED;
    }

    public boolean succeed(long resultMessageId, int resultExitCode, Instant now) {
        if (resultMessageId <= 0L) {
            throw new IllegalArgumentException("assistant message id must be positive");
        }
        requireLifecycleTime(now);
        if (status == ChatRunStatus.SUCCEEDED && assistantMessageId != null
                && assistantMessageId.longValue() == resultMessageId) {
            return false;
        }
        if (status != ChatRunStatus.RUNNING && status != ChatRunStatus.CANCEL_REQUESTED) {
            throw new IllegalChatRunTransitionException(status, ChatRunStatus.SUCCEEDED);
        }
        status = ChatRunStatus.SUCCEEDED;
        assistantMessageId = resultMessageId;
        exitCode = resultExitCode;
        finishedAt = now;
        touch(now);
        return true;
    }

    public boolean fail(String code, String publicMessage, Integer resultExitCode, Instant now) {
        String normalizedCode = requireText(code, "failure code");
        String normalizedMessage = requireText(publicMessage, "public error message");
        requireLifecycleTime(now);
        if (status == ChatRunStatus.FAILED) {
            return false;
        }
        if (status != ChatRunStatus.PENDING && status != ChatRunStatus.RUNNING
                && status != ChatRunStatus.CANCEL_REQUESTED) {
            throw new IllegalChatRunTransitionException(status, ChatRunStatus.FAILED);
        }
        status = ChatRunStatus.FAILED;
        failureCode = normalizedCode;
        errorMessage = normalizedMessage;
        exitCode = resultExitCode;
        finishedAt = now;
        touch(now);
        return true;
    }

    public boolean cancel(Integer resultExitCode, Instant now) {
        requireLifecycleTime(now);
        if (status == ChatRunStatus.CANCELLED) {
            return false;
        }
        if (status != ChatRunStatus.PENDING && status != ChatRunStatus.CANCEL_REQUESTED) {
            throw new IllegalChatRunTransitionException(status, ChatRunStatus.CANCELLED);
        }
        status = ChatRunStatus.CANCELLED;
        exitCode = resultExitCode;
        finishedAt = now;
        touch(now);
        return true;
    }

    public boolean interrupt(String publicMessage, Instant now) {
        String normalizedMessage = requireText(publicMessage, "public error message");
        requireLifecycleTime(now);
        if (status == ChatRunStatus.INTERRUPTED) {
            return false;
        }
        if (!status.isActive()) {
            throw new IllegalChatRunTransitionException(status, ChatRunStatus.INTERRUPTED);
        }
        status = ChatRunStatus.INTERRUPTED;
        failureCode = "SERVER_RESTARTED";
        errorMessage = normalizedMessage;
        finishedAt = now;
        touch(now);
        return true;
    }

    public EventSequenceRange allocateEventSequence(int batchSize, Instant now) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("event batch size must be positive");
        }
        requireLifecycleTime(now);
        long start = lastEventSeq + 1L;
        long end;
        try {
            end = Math.addExact(lastEventSeq, batchSize);
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("event sequence overflow", ex);
        }
        lastEventSeq = end;
        touch(now);
        return new EventSequenceRange(start, end);
    }

    public ChatRunCompletionDecision decideCompletion(int resultExitCode, boolean hasCompleteOutput) {
        if (status == ChatRunStatus.CANCEL_REQUESTED) {
            return resultExitCode == 0 && hasCompleteOutput
                    ? ChatRunCompletionDecision.SUCCEED
                    : ChatRunCompletionDecision.CANCEL;
        }
        if (status == ChatRunStatus.RUNNING) {
            return resultExitCode == 0 && hasCompleteOutput
                    ? ChatRunCompletionDecision.SUCCEED
                    : ChatRunCompletionDecision.FAIL;
        }
        throw new IllegalStateException("run is not waiting for execution completion: " + status);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public boolean isActive() {
        return status.isActive();
    }

    public void synchronizeVersion(long persistedVersion) {
        if (persistedVersion < version) {
            throw new IllegalArgumentException("persisted version must not move backwards");
        }
        version = persistedVersion;
    }

    private void validateRestoredState() {
        if (startedAt != null && startedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("started time must not be before created time");
        }
        if (finishedAt != null && finishedAt.isBefore(startedAt == null ? createdAt : startedAt)) {
            throw new IllegalArgumentException("finished time must not precede lifecycle start");
        }
        if (status.isTerminal() != (finishedAt != null)) {
            throw new IllegalArgumentException("finished time must exist exactly for terminal runs");
        }
        if (status == ChatRunStatus.SUCCEEDED
                && (assistantMessageId == null || assistantMessageId.longValue() <= 0L)) {
            throw new IllegalArgumentException("successful run requires assistant message id");
        }
        if ((status == ChatRunStatus.FAILED || status == ChatRunStatus.INTERRUPTED)
                && (isBlank(failureCode) || isBlank(errorMessage))) {
            throw new IllegalArgumentException("failed or interrupted run requires a public error");
        }
    }

    private void requireTransition(ChatRunStatus expected, ChatRunStatus target) {
        if (status != expected) {
            throw new IllegalChatRunTransitionException(status, target);
        }
    }

    private void requireLifecycleTime(Instant now) {
        Instant requiredAfter = startedAt == null ? createdAt : startedAt;
        if (now == null || now.isBefore(requiredAfter)) {
            throw new IllegalArgumentException("lifecycle time must not move backwards");
        }
    }

    private void touch(Instant now) {
        updatedAt = now;
    }

    private static ChatRunId requireId(ChatRunId id) {
        if (id == null) {
            throw new IllegalArgumentException("chat run id must not be null");
        }
        return id;
    }

    private static ChatRunStatus requireStatus(ChatRunStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("chat run status must not be null");
        }
        return status;
    }

    private static Instant requireTime(Instant value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeIdempotencyKey(String value) {
        String normalized = requireText(value, "idempotency key");
        if (normalized.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotency key must contain at most 128 characters");
        }
        return normalized;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
