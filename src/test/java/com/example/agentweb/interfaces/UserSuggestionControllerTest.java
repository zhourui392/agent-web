package com.example.agentweb.interfaces;

import com.example.agentweb.app.suggestion.UserSuggestionService;
import com.example.agentweb.domain.suggestion.UserSuggestion;
import com.example.agentweb.domain.suggestion.UserSuggestionStatus;
import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.infra.auth.ApiKeyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link UserSuggestionController}.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@WebMvcTest(UserSuggestionController.class)
@Import(GlobalExceptionHandler.class)
public class UserSuggestionControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private UserSuggestionService service;

    @MockBean
    private ApiKeyProperties apiKeyProperties;
    @MockBean
    private AuthProperties authProperties;

    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;

    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    @Test
    public void submit_should_return_created_suggestion() throws Exception {
        when(service.submit(eq("标题"), eq("内容"), eq("wx")))
                .thenReturn(item("s1", UserSuggestionStatus.PENDING, null));

        mvc.perform(post("/api/user-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"标题\",\"content\":\"内容\",\"contact\":\"wx\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("s1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.statusLabel").value("待处理"));

        verify(service).submit("标题", "内容", "wx");
    }

    @Test
    public void submit_blank_content_should_fail_validation() throws Exception {
        mvc.perform(post("/api/user-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"标题\",\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void mine_should_return_my_suggestions() throws Exception {
        when(service.listMine(20)).thenReturn(Collections.singletonList(
                item("s1", UserSuggestionStatus.REPLIED, "已处理")));

        mvc.perform(get("/api/user-suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("s1"))
                .andExpect(jsonPath("$[0].status").value("REPLIED"))
                .andExpect(jsonPath("$[0].adminReply").value("已处理"));
    }

    @Test
    public void detail_should_return_one_suggestion() throws Exception {
        when(service.getMine("s1")).thenReturn(item("s1", UserSuggestionStatus.PROCESSING, null));

        mvc.perform(get("/api/user-suggestions/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("s1"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    private UserSuggestion item(String id, UserSuggestionStatus status, String reply) {
        Instant now = Instant.parse("2026-06-11T08:00:00Z");
        return new UserSuggestion(id, "u1", "张三", "标题", "内容", "wx",
                status, reply, now, now, reply == null ? null : now);
    }
}
