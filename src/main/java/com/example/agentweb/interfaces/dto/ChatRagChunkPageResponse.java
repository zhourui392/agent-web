package com.example.agentweb.interfaces.dto;

import com.example.agentweb.app.refinery.RefineryChunkPage;
import lombok.Getter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * chat-rag 召回库存的分页响应包装. {@code total} 为当前过滤口径 (全部/可召回) 下的总行数,
 * 供前端 el-pagination 渲染页码。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-01
 */
@Getter
public class ChatRagChunkPageResponse {

    private final List<ChatRagChunkResponse> items;
    private final long total;
    private final int page;
    private final int size;

    public ChatRagChunkPageResponse(List<ChatRagChunkResponse> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static ChatRagChunkPageResponse from(RefineryChunkPage page) {
        List<ChatRagChunkResponse> items = page.items().stream()
                .map(ChatRagChunkResponse::from)
                .collect(Collectors.toList());
        return new ChatRagChunkPageResponse(items, page.total(), page.page(), page.size());
    }
}
