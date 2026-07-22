package com.example.agentweb;

import com.example.agentweb.app.ChatAppService;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.infra.EnvProperties;
import com.example.agentweb.interfaces.ChatController;
import com.example.agentweb.interfaces.dto.TruncateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Interface 切片测试：仅启动 MVC 上下文 + 4 个 ChatController 依赖 mock，
 * 验证 rewind 相关 HTTP 端点的参数透传、状态码、响应字段。
 *
 * <p>覆盖范围：</p>
 * <ul>
 *   <li>{@code GET /api/chat/session/{id}/messages} —— 返回 MessageDto 含 id 字段</li>
 *   <li>{@code DELETE /api/chat/session/{id}/messages?fromId=} —— 透传到 ChatAppService.truncateFrom
 *       并把 TruncateResult 序列化到 JSON</li>
 *   <li>{@code ChatMessage} 默认 / 含 id 构造器行为（纯 POJO，无需 Spring）</li>
 * </ul>
 *
 * <p>从原 {@code @SpringBootTest} 下沉到 {@code @WebMvcTest(ChatController.class)},
 * 不再加载 SQLite / ChatAppService / Filter 等无关 Bean,启动从 5-10s 降到 &lt;1s。</p>
 *
 * <p>原 {@code @SpringBootTest} 中覆盖的其他场景已迁移：</p>
 * <ul>
 *   <li>App 层 {@code truncateFrom} 编排 / {@code streamMessage} 历史前缀注入 →
 *       {@link com.example.agentweb.app.RewindAppServiceTest}</li>
 *   <li>Repository 持久化（消息 id 自增、truncate 行为）→
 *       {@link com.example.agentweb.infra.SqliteSessionRepoTest}</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-25
 */
@WebMvcTest(ChatController.class)
public class RewindFeatureTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ChatAppService appService;

    @MockBean
    private com.example.agentweb.app.ChatSessionQueryService sessionQueryService;

    @MockBean
    private EnvProperties envProperties;

    @MockBean
    private SlashCommandExpander commandExpander;

    @MockBean
    private com.example.agentweb.infra.setting.RuntimeAgentSettings runtimeAgentSettings;

    /** {@code @WebMvcTest} 会扫描 Filter Bean,需补齐其构造依赖以免 ApplicationContext 加载失败。 */
    @MockBean
    private AuthProperties authProperties;

    /** SessionAuthFilter 构造依赖, 扫描 Filter Bean 时需补齐。 */


    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;


    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;

    /** SessionAuthFilter 构造依赖, 扫描 Filter Bean 时需补齐。 */

    /** 手动登录链路依赖, SessionAuthFilter 现在还要 manual provider + props + repo, 切片测试一并 mock。 */
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    // ── 1. Domain: ChatMessage.id (纯 POJO, 无需 Spring 上下文也能跑在本类内) ──

    @Test
    public void chatMessage_should_have_id_field() {
        ChatMessage withoutId = new ChatMessage("user", "hi");
        assertNull(withoutId.getId(), "默认构造器不应分配 id");

        ChatMessage withId = new ChatMessage(42L, "user", "hi", Instant.now());
        assertEquals(42L, withId.getId().longValue());
    }

    // ── 2. API: GET /messages 返回 id ──

    @Test
    public void messages_api_should_return_id() throws Exception {
        String sessionId = "sess-msg-id";
        when(sessionQueryService.findMessageViews(sessionId)).thenReturn(java.util.Collections.singletonList(
                new com.example.agentweb.app.ChatMessageView(7L, "user", "msg-with-id",
                        Instant.now().toString(), null)));

        mvc.perform(get("/api/chat/session/" + sessionId + "/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].content").value("msg-with-id"));
    }

    // ── 3. API: DELETE /messages 透传 TruncateResult ──

    @Test
    public void delete_messages_api_should_truncate_and_return_result() throws Exception {
        String sessionId = "sess-truncate";
        long fromId = 42L;
        TruncateResult mockResult = new TruncateResult(2, "drop from here", true);
        when(appService.truncateFrom(sessionId, fromId)).thenReturn(mockResult);

        mvc.perform(delete("/api/chat/session/" + sessionId + "/messages")
                        .param("fromId", String.valueOf(fromId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(2))
                .andExpect(jsonPath("$.prefillContent").value("drop from here"))
                .andExpect(jsonPath("$.resumeIdCleared").value(true));

        // 编排细节 (清 resumeId / 删消息真发生) 属于 App 层逻辑, 在 RewindAppServiceTest 单测覆盖。
        // 这里只验证 Controller 把请求参数原样透传给 ChatAppService。
        verify(appService).truncateFrom(eq(sessionId), eq(fromId));
    }
}
