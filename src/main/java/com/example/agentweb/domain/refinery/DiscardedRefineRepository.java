package com.example.agentweb.domain.refinery;

import java.util.List;

/**
 * {@link DiscardedRefineRecord} 的仓储口. 只读 + 追加 + 单条硬删, 无更新语义
 * (丢弃记录一旦落库即不可变).
 *
 * @author zhourui(V33215020)
 * @since 2026-06-04
 */
public interface DiscardedRefineRepository {

    /** 追加一条丢弃记录. */
    void save(DiscardedRefineRecord record);

    /** 按 created_at 倒序分页. */
    List<DiscardedRefineRecord> findPage(int offset, int limit);

    /** 总条数, 供分页器渲染页码. */
    long count();

    /** 硬删除单条, 命中返回 true. */
    boolean deleteById(String id);
}
