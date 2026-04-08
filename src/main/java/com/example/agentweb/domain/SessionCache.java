package com.example.agentweb.domain;

/**
 * 会话内存缓存端口，用于在请求间快速查找活跃会话。
 * <p>持久化由 {@link SessionRepository} 负责，本接口仅管理内存中的会话引用。</p>
 */
public interface SessionCache {

    /**
     * 将会话放入缓存。
     *
     * @param session 待缓存的会话
     */
    void save(ChatSession session);

    /**
     * 按 ID 查找缓存中的会话。
     *
     * @param id 会话 ID
     * @return 缓存中的会话，未命中返回 null
     */
    ChatSession find(String id);

    /**
     * 从缓存中移除指定会话。
     *
     * @param id 会话 ID
     */
    void remove(String id);
}
