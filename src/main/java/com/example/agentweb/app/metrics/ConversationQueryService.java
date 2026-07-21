package com.example.agentweb.app.metrics;

/**
 * 管理后台「所有用户的对话记录」读侧(CQRS 查询端口)。纯 SELECT 投影,返回 DTO,不经聚合根、不做用户隔离。
 *
 * <p>接口置于 app 层,实现走 infra 直连 {@code JdbcTemplate};调用方(Controller)只依赖此接口。
 * 与 {@code SessionRepository} 的差异:后者按当前用户隔离(普通用户仅见自己),此处是 admin 全量视角。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public interface ConversationQueryService {

    /**
     * 分页列出全部用户的对话记录,按创建时间倒序。
     *
     * @param page    1-based 页码(调用方负责 clamp)
     * @param size    每页条数(调用方负责 clamp)
     * @param keyword 可选关键字,按标题 / 用户名 / 工号模糊匹配;为空则不过滤
     */
    ConversationPage list(int page, int size, String keyword);

    /**
     * 单条对话记录详情(摘要 + 完整消息流);会话不存在返回 {@code null}。
     */
    ConversationDetail detail(String sessionId);
}
