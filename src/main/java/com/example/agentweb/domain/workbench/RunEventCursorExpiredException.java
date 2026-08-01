package com.example.agentweb.domain.workbench;

import lombok.Getter;

/**
 * 请求的 Run 事件游标早于不可变保留窗口。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RunEventCursorExpiredException
        extends RuntimeException {

    private final long earliestRetainedSequence;
    private final long lastEventSequence;

    RunEventCursorExpiredException(
            long earliestRetainedSequence,
            long lastEventSequence) {
        super("run event cursor expired");
        this.earliestRetainedSequence = earliestRetainedSequence;
        this.lastEventSequence = lastEventSequence;
    }
}
