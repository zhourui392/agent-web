package com.example.agentweb.interfaces;

import com.example.agentweb.app.suggestion.UserSuggestionService;
import com.example.agentweb.domain.suggestion.UserSuggestion;
import com.example.agentweb.interfaces.dto.UserSuggestionRequest;
import com.example.agentweb.interfaces.dto.UserSuggestionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话页用户建议接口:提交建议、查看自己的处理状态和回复。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@RestController
@RequestMapping(path = "/api/user-suggestions", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class UserSuggestionController {

    private final UserSuggestionService service;

    public UserSuggestionController(UserSuggestionService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserSuggestionResponse submit(@Valid @RequestBody UserSuggestionRequest req) {
        UserSuggestion suggestion = service.submit(req.getTitle(), req.getContent(), req.getContact());
        log.info("user-suggestion-submit-request id={} status={}", suggestion.getId(), suggestion.getStatus());
        return UserSuggestionResponse.from(suggestion);
    }

    @GetMapping
    public List<UserSuggestionResponse> mine(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return service.listMine(limit).stream()
                .map(UserSuggestionResponse::from)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public UserSuggestionResponse detail(@PathVariable("id") String id) {
        return UserSuggestionResponse.from(service.getMine(id));
    }
}
