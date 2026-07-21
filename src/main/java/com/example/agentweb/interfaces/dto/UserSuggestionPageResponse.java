package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.suggestion.UserSuggestionPage;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户建议分页响应。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@Getter
public class UserSuggestionPageResponse {

    private final List<UserSuggestionResponse> rows;
    private final long total;
    private final int page;
    private final int size;

    public UserSuggestionPageResponse(List<UserSuggestionResponse> rows, long total, int page, int size) {
        this.rows = rows;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static UserSuggestionPageResponse from(UserSuggestionPage page) {
        List<UserSuggestionResponse> rows = page.getRows().stream()
                .map(UserSuggestionResponse::from)
                .collect(Collectors.toList());
        return new UserSuggestionPageResponse(rows, page.getTotal(), page.getPage(), page.getSize());
    }
}
