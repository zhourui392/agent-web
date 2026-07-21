package com.example.agentweb.domain.refinery;

import java.util.Optional;

/**
 * chat_session_rag_state 仓库. 实现位于 infra.refinery.persistence.
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public interface SessionRefineryStateRepository {

    /** 写入或覆盖 (UPSERT) 一条 state 记录. */
    void save(SessionRefineryState state);

    /** 按 sessionId 加载, 不存在返回 Optional.empty(). */
    Optional<SessionRefineryState> findBySessionId(String sessionId);

    /**
     * 删除某 session 的幂等 state. 用于"清空并重跑": 不删 state 的话
     * {@code refineAndIngest} 会因 lastMessageAtSeen 相等而跳过, 导致重跑变 no-op。
     *
     * @param sessionId 会话 ID
     */
    void deleteBySessionId(String sessionId);
}
