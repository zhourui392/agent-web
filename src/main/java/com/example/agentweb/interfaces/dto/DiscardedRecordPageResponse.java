package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import java.util.List;

/**
 * 丢弃记录的分页响应包装, 镜像 {@link ChatRagChunkPageResponse}.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-04
 */
@Getter
public class DiscardedRecordPageResponse {

    private final List<DiscardedRecordResponse> items;
    private final long total;
    private final int page;
    private final int size;

    public DiscardedRecordPageResponse(List<DiscardedRecordResponse> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }
}
