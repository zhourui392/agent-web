package com.example.agentweb.app;

import java.util.List;

/**
 * 会话读模型查询接口（CQRS 读侧）。列表摘要 / 消息回放 / 分享页视图等纯 SELECT 投影，
 * 与写侧 {@link com.example.agentweb.domain.chat.SessionRepository}（聚合 lifecycle）分治。
 *
 * <p>实现放 infra；除分享视图外均须与写侧同样遵守用户隔离
 * （{@code agent.chat.user-isolation-enabled}）。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
public interface ChatSessionQueryService {

    /**
     * 分页会话摘要，按创建时间倒序；受用户隔离过滤。
     *
     * @param offset 跳过行数
     * @param limit  单页上限
     * @return 当前页摘要列表
     */
    List<ChatSessionSummary> findSummaryPaged(int offset, int limit);

    /**
     * 某会话的全部消息（含召回回放 JSON），按消息 ID 升序；受用户隔离过滤。
     *
     * @param sessionId 会话 ID
     * @return 消息视图列表；会话不存在或当前用户不可见时返回 null
     */
    List<ChatMessageView> findMessageViews(String sessionId);

    /**
     * 分享页视图（公开访问，不做用户隔离——token 即授权）。
     *
     * @param shareToken 分享 token
     * @return 分享视图；token 无效时返回 null
     */
    SharedSessionView findSharedView(String shareToken);
}
