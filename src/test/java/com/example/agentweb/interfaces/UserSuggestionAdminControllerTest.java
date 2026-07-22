package com.example.agentweb.interfaces;

import com.example.agentweb.app.suggestion.UserSuggestionService;
import com.example.agentweb.domain.suggestion.UserSuggestion;
import com.example.agentweb.domain.suggestion.UserSuggestionPage;
import com.example.agentweb.domain.suggestion.UserSuggestionStatus;
import com.example.agentweb.infra.auth.AuthProperties;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link UserSuggestionAdminController}.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@WebMvcTest(UserSuggestionAdminController.class)
@Import(GlobalExceptionHandler.class)
public class UserSuggestionAdminControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private UserSuggestionService service;

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
    public void list_should_return_page_and_passthrough_filters() throws Exception {
        UserSuggestionPage page = new UserSuggestionPage(
                Collections.singletonList(item("s1", UserSuggestionStatus.PENDING, null)),
                1L, 1, 20);
        when(service.listForAdmin(eq("PENDING"), eq("按钮"), eq(1), eq(20))).thenReturn(page);

        mvc.perform(get("/api/admin-user-suggestions")
                        .param("status", "PENDING")
                        .param("keyword", "按钮"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].id").value("s1"))
                .andExpect(jsonPath("$.rows[0].statusLabel").value("待处理"));

        verify(service).listForAdmin("PENDING", "按钮", 1, 20);
    }

    @Test
    public void update_should_return_updated_status_and_reply() throws Exception {
        when(service.updateByAdmin(eq("s1"), eq("REPLIED"), eq("已回复")))
                .thenReturn(item("s1", UserSuggestionStatus.REPLIED, "已回复"));

        mvc.perform(patch("/api/admin-user-suggestions/s1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REPLIED\",\"adminReply\":\"已回复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("s1"))
                .andExpect(jsonPath("$.status").value("REPLIED"))
                .andExpect(jsonPath("$.adminReply").value("已回复"));

        verify(service).updateByAdmin("s1", "REPLIED", "已回复");
    }

    @Test
    public void update_blank_status_should_fail_validation() throws Exception {
        mvc.perform(patch("/api/admin-user-suggestions/s1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"\",\"adminReply\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    private UserSuggestion item(String id, UserSuggestionStatus status, String reply) {
        Instant now = Instant.parse("2026-06-11T08:00:00Z");
        return new UserSuggestion(id, "u1", "张三", "标题", "内容", "wx",
                status, reply, now, now, reply == null ? null : now);
    }
}
