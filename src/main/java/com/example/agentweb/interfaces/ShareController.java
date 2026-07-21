package com.example.agentweb.interfaces;

import com.example.agentweb.app.ChatAppService;
import com.example.agentweb.app.ChatSessionQueryService;
import com.example.agentweb.app.SharedSessionView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.Map;

/**
 * 会话分享接口。业务编排（token 生成、会话解析）在 {@link ChatAppService}，
 * 分享页读模型在 {@link ChatSessionQueryService}，本 Controller 只做参数与 DTO。
 *
 * @author zhourui(V33215020)
 */
@Slf4j
@RestController
public class ShareController {

    private final ChatAppService chatAppService;
    private final ChatSessionQueryService sessionQueryService;

    public ShareController(ChatAppService chatAppService, ChatSessionQueryService sessionQueryService) {
        this.chatAppService = chatAppService;
        this.sessionQueryService = sessionQueryService;
    }

    /**
     * Generate (or return existing) share token for a session.
     */
    @PostMapping(path = "/api/chat/session/{id}/share", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> share(@PathVariable("id") String id) {
        String token = chatAppService.shareSession(id);
        return Collections.singletonMap("shareToken", token);
    }

    /**
     * Public endpoint: get shared session messages by token (no auth required).
     */
    @GetMapping(path = "/api/share/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SharedSessionView getShared(@PathVariable("token") String token) {
        SharedSessionView view = sessionQueryService.findSharedView(token);
        if (view == null) {
            throw new IllegalArgumentException("Shared session not found");
        }
        return view;
    }

    /**
     * Public endpoint: continue the shared conversation (no auth required).
     *
     * <p>token → 会话解析与 resumeId/env 选取在 {@link ChatAppService#streamSharedMessage}，
     * 前端无需跟踪 CLI thread。</p>
     */
    @GetMapping(path = "/api/share/{token}/message/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter streamShared(@PathVariable("token") String token,
                                   @RequestParam("message") String message,
                                   @RequestParam(value = "recall", required = false, defaultValue = "true") boolean recall) {
        return chatAppService.streamSharedMessage(token, message, recall);
    }
}
