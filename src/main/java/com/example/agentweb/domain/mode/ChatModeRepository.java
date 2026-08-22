package com.example.agentweb.domain.mode;

import java.util.Optional;

/**
 * ChatMode 聚合的写侧仓储（只负责聚合生命周期）。
 *
 * @author alex
 * @since 2026-08-20
 */
public interface ChatModeRepository {

    /** 新建或按 id + 乐观版本覆盖保存；(userId, identifier) 冲突抛领域异常。 */
    ChatMode save(ChatMode mode);

    Optional<ChatMode> find(String id);

    /** 乐观删除：版本不符抛冲突异常，供并发编辑兜底。 */
    void delete(String id, int expectedVersion);
}
