package com.example.agentweb.app.mode;

/**
 * 交接转录事实查询端口（读 chat_message / chat_run_event 投影）。
 *
 * @author alex
 * @since 2026-08-20
 */
public interface HandoffTranscriptQueryService {

    /**
     * 装配某会话的交接事实。
     *
     * @param sessionId 源会话
     * @param tailLimit 尾部保留消息条数（含 user/assistant）
     */
    HandoffTranscriptFacts loadTranscript(String sessionId, int tailLimit);
}
