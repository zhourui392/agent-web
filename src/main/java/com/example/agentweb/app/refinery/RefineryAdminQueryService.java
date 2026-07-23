package com.example.agentweb.app.refinery;

/**
 * Knowledge Refinery 管理读模型端口。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface RefineryAdminQueryService {

    /** 查询召回库存分页视图。 */
    RefineryChunkPage findChunks(int page, int size, String status);

    /** 查询低分丢弃记录分页视图。 */
    DiscardedRefinePage findDiscarded(int page, int size);
}
