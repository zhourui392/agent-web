package com.example.agentweb.app.suggestion;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.suggestion.UserSuggestion;
import com.example.agentweb.domain.suggestion.UserSuggestionPage;
import com.example.agentweb.domain.suggestion.UserSuggestionRepository;
import com.example.agentweb.domain.suggestion.UserSuggestionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 用户建议应用服务。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@Service
@Slf4j
public class UserSuggestionService {

    private final UserSuggestionRepository repository;
    private final CurrentUserProvider currentUserProvider;

    public UserSuggestionService(UserSuggestionRepository repository,
                                 CurrentUserProvider currentUserProvider) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
    }

    public UserSuggestion submit(String title, String content, String contact) {
        UserSuggestion suggestion = UserSuggestion.create(
                currentUserProvider.currentUserId(),
                currentUserProvider.currentUserName(),
                title,
                content,
                contact,
                Instant.now());
        repository.save(suggestion);
        log.info("user-suggestion-submitted id={} userId={} hasContact={}",
                suggestion.getId(), suggestion.getUserId(), suggestion.getContact() != null);
        return suggestion;
    }

    public List<UserSuggestion> listMine(int limit) {
        return repository.findByUserId(currentUserProvider.currentUserId(), clamp(limit, 1, 100));
    }

    public UserSuggestion getMine(String id) {
        UserSuggestion suggestion = mustFind(id);
        String currentUserId = currentUserProvider.currentUserId();
        if (currentUserProvider.shouldFilter() && !currentUserId.equals(suggestion.getUserId())) {
            throw new IllegalArgumentException("建议不存在或无权查看: " + id);
        }
        return suggestion;
    }

    public UserSuggestionPage listForAdmin(String status, String keyword, int page, int size) {
        UserSuggestionStatus targetStatus = parseStatusOrNull(status);
        int safePage = Math.max(1, page);
        int safeSize = clamp(size, 1, 100);
        return repository.findPage(targetStatus, keyword, safePage, safeSize);
    }

    public UserSuggestion updateByAdmin(String id, String status, String reply) {
        UserSuggestion existing = mustFind(id);
        UserSuggestionStatus targetStatus = UserSuggestionStatus.parse(status);
        if (targetStatus == null) {
            throw new IllegalArgumentException("建议状态不能为空");
        }
        UserSuggestion updated = existing.updateByAdmin(targetStatus, reply, Instant.now());
        repository.save(updated);
        log.info("user-suggestion-admin-updated id={} status={} hasReply={}",
                id, updated.getStatus(), updated.getAdminReply() != null);
        return updated;
    }

    private UserSuggestion mustFind(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("建议 ID 不能为空");
        }
        UserSuggestion suggestion = repository.findById(id.trim());
        if (suggestion == null) {
            throw new IllegalArgumentException("建议不存在: " + id);
        }
        return suggestion;
    }

    private UserSuggestionStatus parseStatusOrNull(String status) {
        try {
            return UserSuggestionStatus.parse(status);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("无效建议状态: " + status);
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
