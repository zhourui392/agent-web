package com.example.agentweb.app.refinery;

/**
 * "清空并重跑最近一段时间 RAG 数据"操作的结果快照.
 *
 * <p>清理 (删 chunk + 删 state) 是同步完成的, {@code matchedSessions}/{@code chunksDeleted}
 * 反映已落地的清理量; 重跑是后台异步串行的, {@code queued} 表示提交后台重跑的会话数,
 * 实际进度看日志. {@code started=false} 表示已有重跑在进行, 本次只清理未提交新重跑.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-31
 */
public final class RebuildResult {

    private final boolean started;
    private final int days;
    private final int matchedSessions;
    private final int chunksDeleted;
    private final int queued;
    private final String reason;

    private RebuildResult(boolean started, int days, int matchedSessions,
                          int chunksDeleted, int queued, String reason) {
        this.started = started;
        this.days = days;
        this.matchedSessions = matchedSessions;
        this.chunksDeleted = chunksDeleted;
        this.queued = queued;
        this.reason = reason;
    }

    /** 正常发起: 清理完成且已提交后台重跑. */
    public static RebuildResult started(int days, int matchedSessions, int chunksDeleted) {
        return new RebuildResult(true, days, matchedSessions, chunksDeleted, matchedSessions, null);
    }

    /** 已有重跑在跑: 本次完成清理但不重复提交. */
    public static RebuildResult busy(int days, int matchedSessions, int chunksDeleted) {
        return new RebuildResult(false, days, matchedSessions, chunksDeleted, 0, "rebuild-in-progress");
    }

    public boolean isStarted() {
        return started;
    }

    public int getDays() {
        return days;
    }

    public int getMatchedSessions() {
        return matchedSessions;
    }

    public int getChunksDeleted() {
        return chunksDeleted;
    }

    public int getQueued() {
        return queued;
    }

    public String getReason() {
        return reason;
    }
}
