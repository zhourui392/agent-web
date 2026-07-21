package com.example.agentweb.app.metrics;

import lombok.Getter;

import java.util.List;

/**
 * 对话记录分页结果:当前页行 + 总条数,供前端 el-pagination 计算总页数。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Getter
public class ConversationPage {

    private final List<ConversationRecord> rows;
    private final long total;
    /** 1-based 当前页。 */
    private final int page;
    private final int size;

    public ConversationPage(List<ConversationRecord> rows, long total, int page, int size) {
        this.rows = rows;
        this.total = total;
        this.page = page;
        this.size = size;
    }
}
