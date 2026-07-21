package com.example.agentweb.interfaces;

import com.example.agentweb.app.suggestion.UserSuggestionService;
import com.example.agentweb.domain.suggestion.UserSuggestion;
import com.example.agentweb.interfaces.dto.UserSuggestionAdminUpdateRequest;
import com.example.agentweb.interfaces.dto.UserSuggestionPageResponse;
import com.example.agentweb.interfaces.dto.UserSuggestionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 管理后台用户建议接口:查看、回复、修改状态。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@RestController
@RequestMapping(path = "/api/admin-user-suggestions", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class UserSuggestionAdminController {

    private final UserSuggestionService service;

    public UserSuggestionAdminController(UserSuggestionService service) {
        this.service = service;
    }

    @GetMapping
    public UserSuggestionPageResponse list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return UserSuggestionPageResponse.from(service.listForAdmin(status, keyword, page, size));
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserSuggestionResponse update(@PathVariable("id") String id,
                                         @Valid @RequestBody UserSuggestionAdminUpdateRequest req) {
        UserSuggestion updated = service.updateByAdmin(id, req.getStatus(), req.getAdminReply());
        log.info("user-suggestion-admin-update-request id={} status={}", id, updated.getStatus());
        return UserSuggestionResponse.from(updated);
    }
}
