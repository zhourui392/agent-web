package com.example.agentweb.domain.workbench;

import lombok.Getter;

/**
 * 一个 Run 在查询时刻冻结的持久事件保留窗口。
 *
 * <p>最早持久序号为 {@code 0} 且 Run 已产生事件，表示这些事件已经全部
 * 清理；此时对外的最早可恢复位置是 {@code last + 1}，客户端只能从
 * {@code last} 之后恢复为空页。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RunEventRetentionWindow {

    private final long lastEventSequence;
    private final long earliestRetainedSequence;

    private RunEventRetentionWindow(
            long lastEventSequence,
            long earliestPersistedSequence) {
        if (lastEventSequence < 0L
                || earliestPersistedSequence < 0L) {
            throw new IllegalArgumentException(
                    "run event sequences must not be negative");
        }
        if (earliestPersistedSequence > lastEventSequence) {
            throw new IllegalArgumentException(
                    "earliest persisted event must not exceed last event");
        }
        this.lastEventSequence = lastEventSequence;
        this.earliestRetainedSequence = retentionStart(
                lastEventSequence, earliestPersistedSequence);
    }

    public static RunEventRetentionWindow from(
            long lastEventSequence,
            long earliestPersistedSequence) {
        return new RunEventRetentionWindow(
                lastEventSequence, earliestPersistedSequence);
    }

    /**
     * 要求 cursor 能从当前保留窗口继续恢复。
     */
    public void requireReplayAfter(long cursor) {
        requireCursorInsideRun(cursor);
        if (cursor < earliestRetainedSequence - 1L) {
            throw new RunEventCursorExpiredException(
                    earliestRetainedSequence, lastEventSequence);
        }
    }

    /**
     * 判断给定已交付序号之后是否仍存在本次冻结窗口内的事件。
     */
    public boolean hasMoreAfter(long deliveredThrough) {
        requireReplayAfter(deliveredThrough);
        return deliveredThrough < lastEventSequence;
    }

    private void requireCursorInsideRun(long cursor) {
        if (cursor < 0L || cursor > lastEventSequence) {
            throw new IllegalArgumentException(
                    "run event cursor must be within the persisted run boundary");
        }
    }

    private static long retentionStart(
            long lastEventSequence,
            long earliestPersistedSequence) {
        if (earliestPersistedSequence > 0L
                || lastEventSequence == 0L) {
            return earliestPersistedSequence;
        }
        try {
            return Math.addExact(lastEventSequence, 1L);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "fully pruned run event window cannot be represented",
                    failure);
        }
    }
}
