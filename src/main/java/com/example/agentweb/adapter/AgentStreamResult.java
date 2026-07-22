package com.example.agentweb.adapter;

import java.util.Objects;

/**
 * Agent 流式进程的结构化终止结果，避免上层从输出文本猜测超时或输出上限。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public final class AgentStreamResult {

    public enum TerminationReason {
        COMPLETED,
        IDLE_TIMEOUT,
        MAX_RUNTIME_TIMEOUT,
        HARD_TIMEOUT,
        OUTPUT_LIMIT
    }

    private final int exitCode;
    private final TerminationReason terminationReason;

    private AgentStreamResult(int exitCode, TerminationReason terminationReason) {
        this.exitCode = exitCode;
        this.terminationReason = Objects.requireNonNull(terminationReason, "terminationReason");
    }

    public static AgentStreamResult completed(int exitCode) {
        return new AgentStreamResult(exitCode, TerminationReason.COMPLETED);
    }

    public static AgentStreamResult terminated(int exitCode, TerminationReason reason) {
        if (reason == TerminationReason.COMPLETED) {
            throw new IllegalArgumentException("terminated result requires a non-completed reason");
        }
        return new AgentStreamResult(exitCode, reason);
    }

    public int getExitCode() {
        return exitCode;
    }

    public TerminationReason getTerminationReason() {
        return terminationReason;
    }

    public boolean isCompletedNormally() {
        return terminationReason == TerminationReason.COMPLETED;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AgentStreamResult)) {
            return false;
        }
        AgentStreamResult that = (AgentStreamResult) other;
        return exitCode == that.exitCode && terminationReason == that.terminationReason;
    }

    @Override
    public int hashCode() {
        return 31 * exitCode + terminationReason.hashCode();
    }
}
