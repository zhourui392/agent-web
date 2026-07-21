package com.example.agentweb.app.refinery;

/**
 * refinery "清空并重跑"管理编排端口.
 *
 * <p>把"最近 N 天有过消息的会话"的 RAG 数据 (chunk + 幂等 state) 清空, 再后台重跑
 * refine+ingest. 不触碰 {@code chat_session} 会话本体与消息.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-31
 */
public interface RefineryRebuildService {

    /**
     * 清空 last_message_at 在最近 {@code days} 天内的会话的 RAG 数据, 并后台重跑.
     *
     * @param days 回溯天数, 调用方负责校验范围
     * @return 清理统计 + 是否发起重跑
     */
    RebuildResult rebuildRecent(int days);
}
