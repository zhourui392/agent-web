package com.example.agentweb.interfaces;

import com.example.agentweb.app.ChatAppService;
import com.example.agentweb.app.ChatMessageView;
import com.example.agentweb.app.ChatSessionQueryService;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.Feedback;
import com.example.agentweb.domain.chat.FeedbackRating;
import com.example.agentweb.domain.slashcommand.SlashCommand;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.infra.EnvProperties;
import com.example.agentweb.interfaces.dto.SendMessageRequest;
import com.example.agentweb.interfaces.dto.StartSessionRequest;
import com.example.agentweb.interfaces.dto.TruncateResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link ChatController}.
 *
 * <p>聚焦 HTTP 边界:DTO 校验、状态码、{@code @PathVariable} 路由以及旧流式入口的删除契约。
 * CLI 子进程与可恢复 SSE 时序由 ChatRun 相关测试覆盖。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-26
 */
@WebMvcTest(ChatController.class)
@Import(GlobalExceptionHandler.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ChatAppService appService;

    @MockBean
    private ChatSessionQueryService sessionQueryService;

    @MockBean
    private EnvProperties envProperties;

    @MockBean
    private SlashCommandExpander commandExpander;

    @MockBean
    private com.example.agentweb.infra.setting.RuntimeAgentSettings runtimeAgentSettings;

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

    private ChatSession session(String id, AgentType type, String env) {
        ChatSession s = new ChatSession(id, type, "/tmp/work",
                Instant.parse("2026-05-26T10:00:00Z"), Collections.emptyList());
        if (env != null) {
            s.setEnv(env);
        }
        return s;
    }

    @Test
    void startSession_should_return_session_metadata() throws Exception {
        when(appService.startSession(any(StartSessionRequest.class), any()))
                .thenReturn(session("sess-1", AgentType.CLAUDE, "test"));

        String body = "{\"agentType\":\"CLAUDE\",\"workingDir\":\"/tmp/work\",\"env\":\"test\"}";

        mvc.perform(post("/api/chat/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess-1"))
                .andExpect(jsonPath("$.agentType").value("CLAUDE"))
                .andExpect(jsonPath("$.workingDir").value("/tmp/work"))
                .andExpect(jsonPath("$.env").value("test"));
    }

    @Test
    void startSession_should_pass_x_forwarded_for_ip_to_app_service() throws Exception {
        when(appService.startSession(any(StartSessionRequest.class), any()))
                .thenReturn(session("sess-1", AgentType.CLAUDE, "test"));

        String body = "{\"agentType\":\"CLAUDE\",\"workingDir\":\"/tmp/work\"}";

        mvc.perform(post("/api/chat/session")
                        .header("X-Forwarded-For", "9.9.9.9, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
        verify(appService).startSession(any(StartSessionRequest.class), ipCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("9.9.9.9", ipCaptor.getValue());
    }

    @Test
    void startSession_should_return_400_when_working_dir_blank() throws Exception {
        // @NotBlank workingDir
        String body = "{\"agentType\":\"CLAUDE\",\"workingDir\":\"\",\"env\":\"test\"}";

        mvc.perform(post("/api/chat/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation failed"));

        verify(appService, never()).startSession(any(), any());
    }

    @Test
    void sendMessage_should_return_output() throws Exception {
        when(appService.sendMessage(eq("sess-1"), any(SendMessageRequest.class)))
                .thenReturn("hello");

        mvc.perform(post("/api/chat/session/sess-1/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output").value("hello"));
    }

    @Test
    void sendMessage_should_return_400_when_message_blank() throws Exception {
        mvc.perform(post("/api/chat/session/sess-1/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation failed"));

        verify(appService, never()).sendMessage(anyString(), any(SendMessageRequest.class));
    }

    @Test
    void legacyStreamPost_should_not_be_exposed() throws Exception {
        mvc.perform(post("/api/chat/session/sess-1/message/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"env\":\"test\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyStreamGet_should_not_be_exposed() throws Exception {
        mvc.perform(get("/api/chat/session/sess-1/message/stream")
                        .param("message", "secret in URL"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMessages_should_return_400_when_session_not_found() throws Exception {
        when(sessionQueryService.findMessageViews("ghost")).thenReturn(null);

        mvc.perform(get("/api/chat/session/ghost/messages"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("ghost")));
    }

    @Test
    void getMessages_should_return_messages_list() throws Exception {
        when(sessionQueryService.findMessageViews("sess-1")).thenReturn(Arrays.asList(
                new ChatMessageView(1L, "user", "q1", "2026-05-26T10:01:00Z", null),
                new ChatMessageView(2L, "assistant", "a1", "2026-05-26T10:02:00Z", null)));

        mvc.perform(get("/api/chat/session/sess-1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[1].content").value("a1"));
    }

    @Test
    void getMessages_should_attach_recall_payload_on_assistant_message() throws Exception {
        when(sessionQueryService.findMessageViews("sess-1")).thenReturn(Arrays.asList(
                new ChatMessageView(1L, "user", "/recall x", "2026-05-26T10:01:00Z", null),
                new ChatMessageView(2L, "assistant", "ans", "2026-05-26T10:02:00Z",
                        "{\"query\":\"x\",\"hits\":[]}")));

        mvc.perform(get("/api/chat/session/sess-1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].recall").value("{\"query\":\"x\",\"hits\":[]}"));
    }

    @Test
    void deleteSession_should_return_success() throws Exception {
        mvc.perform(delete("/api/chat/session/sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(appService, times(1)).deleteSession("sess-1");
    }

    @Test
    void truncate_should_passthrough_result() throws Exception {
        when(appService.truncateFrom("sess-1", 3L))
                .thenReturn(new TruncateResult(2, "rewind point", true));

        mvc.perform(delete("/api/chat/session/sess-1/messages").param("fromId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(2))
                .andExpect(jsonPath("$.prefillContent").value("rewind point"))
                .andExpect(jsonPath("$.resumeIdCleared").value(true));
    }

    @Test
    void legacySessionStatus_should_not_be_exposed() throws Exception {
        mvc.perform(get("/api/chat/session/sess-1/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacySessionStop_should_not_be_exposed() throws Exception {
        mvc.perform(post("/api/chat/session/sess-1/stop"))
                .andExpect(status().isNotFound());
    }

    @Test
    void envs_should_map_to_dto() throws Exception {
        EnvProperties.EnvEntry test = new EnvProperties.EnvEntry();
        test.setKey("test");
        test.setLabel("Test");
        test.setColor("#0a0");
        EnvProperties.EnvEntry prod = new EnvProperties.EnvEntry();
        prod.setKey("prod");
        prod.setLabel("Prod");
        prod.setColor("#a00");
        when(envProperties.getEnvs()).thenReturn(Arrays.asList(test, prod));

        mvc.perform(get("/api/chat/envs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("test"))
                .andExpect(jsonPath("$[1].color").value("#a00"));
    }

    @Test
    void listCommandsByDir_should_map_to_dto() throws Exception {
        SlashCommand cmd = new SlashCommand("compact", "compact session", "[topic]",
                "do compact", false);
        when(commandExpander.listCommands("/tmp/work")).thenReturn(Collections.singletonList(cmd));

        mvc.perform(get("/api/chat/commands").param("workingDir", "/tmp/work"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("compact"))
                .andExpect(jsonPath("$[0].description").value("compact session"))
                .andExpect(jsonPath("$[0].argumentHint").value("[topic]"));
    }

    @Test
    void putFeedback_should_passthrough_to_appservice() throws Exception {
        Feedback fb = new Feedback(FeedbackRating.CORRECT, "spot on",
                Instant.parse("2026-05-26T11:00:00Z"));
        when(appService.saveFeedback("sess-1", "CORRECT", "spot on")).thenReturn(fb);

        mvc.perform(put("/api/chat/session/sess-1/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":\"CORRECT\",\"comment\":\"spot on\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value("CORRECT"))
                .andExpect(jsonPath("$.comment").value("spot on"))
                .andExpect(jsonPath("$.updatedAt").value("2026-05-26T11:00:00Z"));
    }

    @Test
    void getFeedback_when_never_set_should_return_null_fields() throws Exception {
        when(appService.getFeedback("sess-1")).thenReturn(null);

        mvc.perform(get("/api/chat/session/sess-1/feedback"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"rating\":null,\"comment\":null,\"updatedAt\":null}"));
    }
}
