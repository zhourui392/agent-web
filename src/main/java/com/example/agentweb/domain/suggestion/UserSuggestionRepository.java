package com.example.agentweb.domain.suggestion;

import java.util.List;

/**
 * 用户建议持久化端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
public interface UserSuggestionRepository {

    void save(UserSuggestion suggestion);

    UserSuggestion findById(String id);

    List<UserSuggestion> findByUserId(String userId, int limit);

    UserSuggestionPage findPage(UserSuggestionStatus status, String keyword, int page, int size);
}
