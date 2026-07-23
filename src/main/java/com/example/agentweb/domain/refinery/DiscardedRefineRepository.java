package com.example.agentweb.domain.refinery;

/**
 * {@link DiscardedRefineRecord} 的写侧仓储口。追加 + 单条硬删，无更新语义；
 * 管理分页查询由 App QueryService 承载。
 * (丢弃记录一旦落库即不可变).
 *
 * @author zhourui(V33215020)
 * @since 2026-06-04
 */
public interface DiscardedRefineRepository {

    /** 追加一条丢弃记录. */
    void save(DiscardedRefineRecord record);

    /** 硬删除单条, 命中返回 true. */
    boolean deleteById(String id);
}
