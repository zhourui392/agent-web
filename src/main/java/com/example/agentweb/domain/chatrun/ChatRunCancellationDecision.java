package com.example.agentweb.domain.chatrun;

/**
 * 取消命令的领域决议，明确告诉应用层需要持久化何种变化以及是否停止已启动进程。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public enum ChatRunCancellationDecision {
    UNCHANGED(false, false, false),
    CANCELLED_BEFORE_START(true, true, false),
    REQUESTED(true, false, true);

    private final boolean changed;
    private final boolean terminalTransition;
    private final boolean processStopRequired;

    ChatRunCancellationDecision(boolean changed, boolean terminalTransition,
                                boolean processStopRequired) {
        this.changed = changed;
        this.terminalTransition = terminalTransition;
        this.processStopRequired = processStopRequired;
    }

    public boolean isChanged() {
        return changed;
    }

    public boolean isTerminalTransition() {
        return terminalTransition;
    }

    public boolean isProcessStopRequired() {
        return processStopRequired;
    }
}
