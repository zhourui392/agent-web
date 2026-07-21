package com.example.agentweb.app.refinery;

import com.example.agentweb.domain.refinery.ConversationView;

import java.util.Optional;

/**
 * Knowledge Refinery 应用编排端口. 由 scheduler / 诊断终态钩子 / 手工触发, 不做业务规则.
 *
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public interface RefineryAppService {

    /**
     * 评分一个 chat session, 满足阈值则 embed + 入库 chunk; 否则只更新 state.
     *
     * <p>无论成功 / 失败 / 阈值不通过, 都会更新 {@code chat_session_rag_state} 表.
     * 成功与 below-threshold 将 retry_count 归零且下一轮不再处理 (除非有新消息);
     * 真·失败累加 retry_count, 在达 max-retries 上限前下一轮仍会重试.</p>
     *
     * @param sessionId 会话 ID
     * @return true 当且仅当生成了一条 chunk 入库; false 包含: 阈值不通过, refine 失败,
     *         embed 失败, session 不存在
     */
    boolean refineAndIngest(String sessionId);

    /**
     * 源类型无关的 ingest 入口: 给定一个 {@link ConversationView}, 完成 policy 过滤 → refine
     * → 阈值检查 → embed → chunk 落库. <b>不写任何状态表</b>, 调用方 (chat 自身 / 诊断 trigger /
     * 未来 issue trigger) 负责状态跟踪.
     *
     * @param view 已组装好的对话视图
     * @return 入库 chunk 的 id; 被 policy 拦截 / score 不达标 / refine 失败 → Optional.empty();
     *         严重异常 (CLI 崩溃 / 嵌入 HTTP 失败 / 持久化失败) 上抛, 由调用方记录失败状态
     */
    Optional<String> ingest(ConversationView view);

    /**
     * 存量 chunk 重嵌入（M4 triggerDescription 迁移）：embed 文本构成变化后按批渐进刷新向量,
     * 管理台分批触发. 回滚 = 停止调用（同模型同维度, 旧向量兼容, 无 schema 破坏）.
     *
     * @param limit 本批上限
     * @return 实际刷新条数
     */
    int reembedActive(int limit);
}
