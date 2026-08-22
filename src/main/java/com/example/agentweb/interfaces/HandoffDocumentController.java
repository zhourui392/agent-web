package com.example.agentweb.interfaces;

import com.example.agentweb.app.mode.HandoffFilePort;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.ChatSessionNotFoundException;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.mode.HandoffDocument;
import com.example.agentweb.domain.mode.HandoffDocumentRepository;
import com.example.agentweb.domain.mode.ModeNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/**
 * 交接记录只读查看接口（校验源会话归属）。
 *
 * @author alex
 * @since 2026-08-20
 */
@RestController
@RequestMapping(path = "/api/handoff-documents", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class HandoffDocumentController {

    private final HandoffDocumentRepository handoffRepository;
    private final SessionRepository sessionRepository;
    private final HandoffFilePort handoffFilePort;
    private final CurrentUserProvider currentUserProvider;

    public HandoffDocumentController(HandoffDocumentRepository handoffRepository,
                                     SessionRepository sessionRepository,
                                     HandoffFilePort handoffFilePort,
                                     CurrentUserProvider currentUserProvider) {
        this.handoffRepository = handoffRepository;
        this.sessionRepository = sessionRepository;
        this.handoffFilePort = handoffFilePort;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable("id") String id) {
        HandoffDocument document = handoffRepository.find(id)
                .orElseThrow(() -> new ModeNotFoundException("handoff-" + id));
        ChatSession source = sessionRepository.findById(document.getFromSessionId());
        if (source == null) {
            throw new ChatSessionNotFoundException(document.getFromSessionId());
        }
        // 授权：与模式切换同语义，仅属主（或无归属老数据）可查看交接
        source.requireDeletableBy(currentUserProvider.currentUserId());
        String content = handoffFilePort.readContent(
                source.getWorkingDir(), document.getFilePath());
        return Map.of(
                "id", document.getId(),
                "fromSessionId", document.getFromSessionId(),
                "toSessionId", document.getToSessionId(),
                "fromModeId", document.getFromModeId() == null ? "" : document.getFromModeId(),
                "toModeId", document.getToModeId() == null ? "" : document.getToModeId(),
                "filePath", document.getFilePath(),
                "createdAt", document.getCreatedAt().toString(),
                "content", content == null ? "" : content);
    }
}
