package com.example.agentweb.interfaces;

import com.example.agentweb.app.ChatMessageView;
import com.example.agentweb.app.ChatSessionQueryService;
import com.example.agentweb.app.SharedSessionView;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import com.example.agentweb.infra.auth.AuthProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link ShareController}.
 *
 * <p>聚焦 HTTP 边界:token 生成端点的 JSON 形状、分享页读模型序列化、token 无效 → 400 的异常映射。
 * token 生成与会话解析编排见 {@code ChatAppServiceImplShareTest}。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-26
 */
@WebMvcTest(ShareController.class)
@Import(GlobalExceptionHandler.class)
class ShareControllerTest {

    @TempDir
    Path tempDir;

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ChatSessionQueryService sessionQueryService;

    @MockBean
    private com.example.agentweb.app.ChatAppService chatAppService;

    @MockBean
    private AuthProperties authProperties;

    /** SessionAuthFilter 构造依赖, 扫描 Filter Bean 时需补齐。 */


    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;


    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;

    /** SessionAuthFilter 构造依赖, @WebMvcTest 扫描 Filter Bean 时需补齐 */

    /** 手动登录链路依赖, SessionAuthFilter 现在还要 manual provider + props + repo, 切片测试一并 mock。 */
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    @MockBean
    private WorkspacePathPolicy workspacePathPolicy;

    @Test
    void share_should_return_token_from_app_service() throws Exception {
        when(chatAppService.shareSession("sess-1")).thenReturn("existing-token-abc");

        mvc.perform(post("/api/chat/session/sess-1/share"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shareToken").value("existing-token-abc"));

        verify(chatAppService, times(1)).shareSession("sess-1");
    }

    @Test
    void share_should_return_400_when_session_missing() throws Exception {
        when(chatAppService.shareSession("ghost"))
                .thenThrow(new IllegalArgumentException("Session not found: ghost"));

        mvc.perform(post("/api/chat/session/ghost/share"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("ghost")));
    }

    @Test
    void getShared_should_return_messages_and_meta() throws Exception {
        SharedSessionView view = new SharedSessionView("debug session", "CODEX",
                "2026-05-26T08:00:00Z",
                Arrays.asList(
                        new ChatMessageView(1L, "user", "q", "2026-05-26T08:01:00Z", null),
                        new ChatMessageView(2L, "assistant", "a", "2026-05-26T08:02:00Z", null)));
        when(sessionQueryService.findSharedView("shared-token-xyz")).thenReturn(view);

        mvc.perform(get("/api/share/shared-token-xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("debug session"))
                .andExpect(jsonPath("$.agentType").value("CODEX"))
                .andExpect(jsonPath("$.workingDir").doesNotExist())
                .andExpect(jsonPath("$.createdAt").value("2026-05-26T08:00:00Z"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("a"));
    }

    @Test
    void getShared_should_attach_recall_payload_on_assistant_message() throws Exception {
        SharedSessionView view = new SharedSessionView(null, "CLAUDE",
                "2026-05-26T08:00:00Z",
                Arrays.asList(
                        new ChatMessageView(1L, "user", "/recall x", "2026-05-26T08:01:00Z", null),
                        new ChatMessageView(2L, "assistant", "a", "2026-05-26T08:02:00Z",
                                "{\"query\":\"x\",\"hits\":[]}")));
        when(sessionQueryService.findSharedView("tok")).thenReturn(view);

        mvc.perform(get("/api/share/tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[1].recall").value("{\"query\":\"x\",\"hits\":[]}"));
    }

    @Test
    void getShared_should_return_400_when_token_unknown() throws Exception {
        when(sessionQueryService.findSharedView("nope")).thenReturn(null);

        mvc.perform(get("/api/share/nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("not found")));
    }

    @Test
    void streamShared_should_be_removed_to_keep_share_readOnly() throws Exception {
        mvc.perform(get("/api/share/tok9/message/stream").param("message", "hi there"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sharedImage_should_require_exact_reference_and_allowedRealPath() throws Exception {
        Path image = tempDir.resolve("a.png");
        Files.write(image, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        when(sessionQueryService.isSharedImageReferenced("tok9", image.toString()))
                .thenReturn(true);
        when(workspacePathPolicy.requireExistingFile(image.toString()))
                .thenReturn(image.toString());

        mvc.perform(get("/api/share/tok9/image").param("path", image.toString()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentType(org.springframework.http.MediaType.IMAGE_PNG));
    }

    @Test
    void sharedImage_should_reject_unreferencedPath() throws Exception {
        when(sessionQueryService.isSharedImageReferenced("tok9", "/work/secret.png"))
                .thenReturn(false);

        mvc.perform(get("/api/share/tok9/image").param("path", "/work/secret.png"))
                .andExpect(status().isBadRequest());
    }
}
