package com.example.agentweb.interfaces;

import com.example.agentweb.app.knowledge.KnowledgeInboxAppService;
import com.example.agentweb.app.knowledge.KnowledgeInboxQueryService;
import com.example.agentweb.app.knowledge.KnowledgeSuggestionView;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.knowledge.KnowledgeScope;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 收件箱 MVC slice：状态码矩阵（404/409/422）、actor 取登录用户、列表投影透传。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@WebMvcTest(KnowledgeInboxController.class)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = "agent.requirement.enabled=true")
public class KnowledgeInboxControllerTest {

    private static final String USER = "test-user";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private KnowledgeInboxAppService inboxAppService;
    @MockBean
    private KnowledgeInboxQueryService queryService;
    @MockBean
    private CurrentUserProvider currentUserProvider;

    /** SessionAuthFilter 身份决议统一走 AuthAppService, 切片测试放行所有请求。 */
    @MockBean
    private com.example.agentweb.app.auth.AuthAppService authAppService;
    /** ApiKeyAuthFilter 构造依赖, 切片上下文必须提供。 */
    @MockBean
    private com.example.agentweb.infra.auth.ApiKeyProperties apiKeyProperties;
    @MockBean
    private com.example.agentweb.infra.auth.AuthProperties authProperties;
    @MockBean
    private com.example.agentweb.infra.auth.ThreadLocalUserContext userContext;
    @MockBean
    private com.example.agentweb.domain.auth.ManualSessionRepository manualSessionRepository;

    @BeforeEach
    void stubAuth() {
        when(currentUserProvider.currentUserId()).thenReturn(USER);
    }

    @Test
    public void list_should_return_pending_views() throws Exception {
        when(queryService.listByStatus(eq("PENDING"), eq(50))).thenReturn(List.of(
                new KnowledgeSuggestionView("KS-1", "R1", "REPO", "MR !12", "标题",
                        List.of("信号"), "p", "r", "s", "n", "PENDING", null, null, 1L)));

        mvc.perform(get("/api/knowledge-suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("KS-1"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    public void approve_should_use_login_user_as_actor_and_return_issue_id() throws Exception {
        KnowledgeSuggestion approved = KnowledgeSuggestion.create("R1", KnowledgeScope.REPO,
                "MR !12", "标题", "p", "r", "s");
        approved.approve(USER);
        approved.recordArchived("I-007", "docs/issue-log/issue/I-007-x.md");
        when(inboxAppService.approve("KS-1", USER)).thenReturn(approved);

        mvc.perform(post("/api/knowledge-suggestions/KS-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueId").value("I-007"));

        verify(inboxAppService).approve("KS-1", USER);
    }

    @Test
    public void approve_missing_should_map_404_and_conflict_422_matrix() throws Exception {
        when(inboxAppService.approve("KS-404", USER))
                .thenThrow(new NoSuchElementException("not found"));
        when(inboxAppService.approve("KS-409", USER))
                .thenThrow(new IllegalStateException("workspace missing"));
        when(inboxAppService.approve("KS-422", USER))
                .thenThrow(new IllegalArgumentException("触发词不能为空"));

        mvc.perform(post("/api/knowledge-suggestions/KS-404/approve"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/knowledge-suggestions/KS-409/approve"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/knowledge-suggestions/KS-422/approve"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    public void reject_should_pass_reason_and_return_204() throws Exception {
        mvc.perform(post("/api/knowledge-suggestions/KS-1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"重复知识\"}"))
                .andExpect(status().isNoContent());

        verify(inboxAppService).reject("KS-1", USER, "重复知识");
    }
}
