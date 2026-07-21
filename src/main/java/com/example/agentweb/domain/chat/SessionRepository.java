package com.example.agentweb.domain.chat;

import java.util.List;

/**
 * Port for persisting chat sessions and their messages.
 * @author zhourui(V33215020)
 */
public interface SessionRepository {

    /**
     * 保存或更新会话聚合根 (不含 messages,messages 由 addMessage 单独追加).
     * @param session 会话聚合根
     */
    void saveSession(ChatSession session);

    /**
     * 追加一条消息到会话.
     * @param sessionId 会话 ID
     * @param message 待追加的消息
     */
    void addMessage(String sessionId, ChatMessage message);

    /**
     * 追加一条消息并返回其持久化后的自增 ID. 用于需要把附属数据 (如 /recall 命中) 挂到该消息上的场景.
     * @param sessionId 会话 ID
     * @param message 待追加的消息
     * @return 新消息的主键 id
     */
    long addMessageReturningId(String sessionId, ChatMessage message);

    /**
     * Saves public recall payload for one assistant message. It is a one-to-one
     * overwrite keyed by message id.
     * @param messageId assistant message id
     * @param payloadJson public replay JSON, {@code {query,status,hits:[...]}}
     */
    void saveRecall(long messageId, String payloadJson);

    /**
     * 按 ID 查询会话(含 messages).
     * @param id 会话 ID
     * @return 会话聚合根,不存在则返回 null
     */
    ChatSession findById(String id);

    /**
     * 全量列出所有会话(含 messages),仅供导出/调试使用.
     * @return 会话列表
     */
    List<ChatSession> findAll();

    /**
     * 按 ID 删除会话及其消息.
     * @param id 会话 ID
     */
    void deleteById(String id);

    /**
     * 更新 CLI 侧的 resume id (Claude --resume / Codex thread id).
     * @param sessionId 会话 ID
     * @param resumeId 新的 resume id,null 表示清空
     */
    void updateResumeId(String sessionId, String resumeId);

    /**
     * Deletes all messages whose id is >= fromId in the given session, then clears resume_id.
     * Returns the number of messages actually deleted.
     */
    int truncateFrom(String sessionId, long fromId);

    /**
     * Sets a share token for the given session. Returns the token.
     * @param sessionId 会话 ID
     * @param shareToken 新的分享 token
     * @return 已落库的 token
     */
    String setShareToken(String sessionId, String shareToken);

    /**
     * Finds a session by its share token.
     * @param shareToken 分享 token
     * @return 对应会话,不存在则返回 null
     */
    ChatSession findByShareToken(String shareToken);

    /**
     * 保存(整体覆盖)会话的反馈评价。
     * <p>rating/comment 由调用方组装完整后传入,本方法不做合并。</p>
     * @param sessionId 会话 ID
     * @param feedback 反馈值对象,不为 null
     */
    void saveFeedback(String sessionId, Feedback feedback);

    /**
     * 查询 last_message_at &lt; beforeMs 且 refinery 调度器仍需处理的会话 ID 列表, 按 last_message_at 升序.
     * 供 refinery scheduler 拉取"已静默"会话的入口.
     * 返回纯 ID 列表 (CQRS 读侧), 不装配聚合根, 不带 messages, 避免大对象浪费.
     *
     * <p>LEFT JOIN {@code chat_session_rag_state} 后, 仅返回真正待处理者, 避免已处理的旧会话
     * 永久占满 {@code limit} 窗口把新会话/待重试会话饿死。入选条件 (满足其一):</p>
     * <ul>
     *   <li>从未评过 (无 state 行)</li>
     *   <li>有新消息 (state.last_message_at_seen 与当前 last_message_at 不等)</li>
     *   <li>上次真·失败 (last_error 非 null 且非 belowThresholdSentinel) 且 retry_count &lt; maxRetries</li>
     * </ul>
     * <p>已成功 (last_error=null) 或 below-threshold 且无新消息的会话被排除; 真·失败达上限也被排除。</p>
     *
     * @param beforeMs last_message_at 必须严格小于此 epoch millis 才入选
     * @param belowThresholdSentinel {@code last_error} 中代表"评分不达标, 不重试"的哨兵值
     * @param maxRetries 真·失败会话的重试上限, retry_count 达到此值后不再入选
     * @param limit 单次返回上限, 用于节流
     * @return 候选会话 ID, 可能为空
     */
    List<String> findIdsWithLastMessageBefore(long beforeMs, String belowThresholdSentinel,
                                              int maxRetries, int limit);

    /**
     * 查询 last_message_at &gt;= afterMs 的会话 ID 列表, 按 last_message_at 升序.
     * 供 refinery 管理操作"清空并重跑最近一段时间"的入口, 全量返回不节流.
     *
     * @param afterMs last_message_at 必须大于等于此 epoch millis 才入选
     * @return 命中会话 ID, 可能为空
     */
    List<String> findIdsWithLastMessageAfter(long afterMs);
}
