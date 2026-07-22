package com.example.agentweb.interfaces;

import com.example.agentweb.app.ChatAppService;
import com.example.agentweb.app.ChatSessionQueryService;
import com.example.agentweb.app.SharedSessionView;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
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
    private final WorkspacePathPolicy workspacePathPolicy;

    public ShareController(ChatAppService chatAppService, ChatSessionQueryService sessionQueryService,
                           WorkspacePathPolicy workspacePathPolicy) {
        this.chatAppService = chatAppService;
        this.sessionQueryService = sessionQueryService;
        this.workspacePathPolicy = workspacePathPolicy;
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
     * 公开分享图片端点。token 和消息中的精确图片引用共同构成授权。
     */
    @GetMapping(path = "/api/share/{token}/image")
    public ResponseEntity<Resource> image(@PathVariable("token") String token,
                                          @org.springframework.web.bind.annotation.RequestParam("path") String path) {
        if (!sessionQueryService.isSharedImageReferenced(token, path)) {
            throw new IllegalArgumentException("Shared image not found");
        }
        String realPath = workspacePathPolicy.requireExistingFile(path);
        File file = new File(realPath);
        return ResponseEntity.ok()
                .contentType(imageMediaType(file.getName()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new FileSystemResource(file));
    }

    private MediaType imageMediaType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".bmp")) {
            return MediaType.parseMediaType("image/bmp");
        }
        throw new IllegalArgumentException("Not an image file: " + fileName);
    }
}
