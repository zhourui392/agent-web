package com.example.agentweb.domain.suggestion;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 用户建议分页结果。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@Getter
public final class UserSuggestionPage {

    private final List<UserSuggestion> rows;
    private final long total;
    private final int page;
    private final int size;

    public UserSuggestionPage(List<UserSuggestion> rows, long total, int page, int size) {
        this.rows = rows == null ? Collections.emptyList() : rows;
        this.total = total;
        this.page = page;
        this.size = size;
    }
}
