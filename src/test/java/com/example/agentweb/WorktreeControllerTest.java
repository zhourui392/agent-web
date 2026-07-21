package com.example.agentweb;

import com.example.agentweb.app.WorktreeService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.infra.auth.ApiKeyProperties;
import com.example.agentweb.interfaces.GlobalExceptionHandler;
import com.example.agentweb.interfaces.WorktreeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice test for {@link WorktreeController}.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@WebMvcTest(WorktreeController.class)
@Import(GlobalExceptionHandler.class)
class WorktreeControllerTest {

    private static final String USER_ID = "ou_test";
    private static final String WORKSPACE = "E:/repo/ws";
    private static final String BRANCH = "feature/test-branch";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private WorktreeService worktreeService;
    @MockBean
    private CurrentUserProvider currentUserProvider;
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
    @DisplayName("POST /api/worktree/switch 创建 worktree 并返回结果")
    void switchBranch_returns200WithWorktreePath() throws Exception {
        // Given
        mockCurrentUser();
        when(worktreeService.switchBranch(USER_ID, WORKSPACE, BRANCH)).thenReturn(buildSwitchResult());

        // When & Then
        mvc.perform(post("/api/worktree/switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(switchBody(WORKSPACE, BRANCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worktreePath").value("E:/repo/ws/.worktrees/u-ou_test/feature-test-branch"))
                .andExpect(jsonPath("$.branch").value(BRANCH))
                .andExpect(jsonPath("$.repos[0].name").value("svc-alpha"))
                .andExpect(jsonPath("$.repos[0].created").value(true));
    }

    @Test
    @DisplayName("GET /api/worktree/list 列出已创建的 worktree")
    void listWorktrees_returnsCreatedBranches() throws Exception {
        // Given
        mockCurrentUser();
        when(worktreeService.listWorktrees(USER_ID, WORKSPACE))
                .thenReturn(Collections.singletonList(buildBranchItem()));

        // When & Then
        mvc.perform(get("/api/worktree/list").param("workspacePath", WORKSPACE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].branch").value("feature-test-branch"))
                .andExpect(jsonPath("$[0].repoCount").value(1));
    }

    @Test
    @DisplayName("DELETE /api/worktree/remove 删除指定分支的 worktree")
    void removeWorktree_deletesAndReturnsSuccess() throws Exception {
        // Given
        mockCurrentUser();

        // When & Then
        mvc.perform(delete("/api/worktree/remove")
                        .param("workspacePath", WORKSPACE)
                        .param("branch", BRANCH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(worktreeService).removeWorktree(USER_ID, WORKSPACE, BRANCH);
    }

    @Test
    @DisplayName("POST /api/worktree/switch 无效路径返回 400")
    void switchBranch_invalidPath_returns400() throws Exception {
        // Given
        mockCurrentUser();
        doThrow(new IllegalArgumentException("Workspace not found: /nonexistent/path"))
                .when(worktreeService).switchBranch(eq(USER_ID), eq("/nonexistent/path"), anyString());

        // When & Then
        mvc.perform(post("/api/worktree/switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(switchBody("/nonexistent/path", "feature/x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Workspace not found")));
    }

    @Test
    @DisplayName("POST /api/worktree/switch 分支名前缀不合规返回 400")
    void switchBranch_disallowedPrefix_returns400() throws Exception {
        mvc.perform(post("/api/worktree/switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(switchBody(WORKSPACE, "master")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("必须以")));
    }

    @Test
    @DisplayName("POST /api/worktree/switch 自动去除分支名前后空格")
    void switchBranch_trimsWhitespace() throws Exception {
        // Given
        mockCurrentUser();
        when(worktreeService.switchBranch(USER_ID, WORKSPACE, BRANCH)).thenReturn(buildSwitchResult());

        // When & Then
        mvc.perform(post("/api/worktree/switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(switchBody(WORKSPACE, "  " + BRANCH + "  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value(BRANCH));

        verify(worktreeService).switchBranch(USER_ID, WORKSPACE, BRANCH);
    }

    @Test
    @DisplayName("GET /api/worktree/list 无 worktree 时返回空数组")
    void listWorktrees_empty_returnsEmptyArray() throws Exception {
        // Given
        mockCurrentUser();
        when(worktreeService.listWorktrees(USER_ID, WORKSPACE)).thenReturn(Collections.emptyList());

        // When & Then
        mvc.perform(get("/api/worktree/list").param("workspacePath", WORKSPACE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    private void mockCurrentUser() {
        when(currentUserProvider.currentUserId()).thenReturn(USER_ID);
    }

    private String switchBody(String workspacePath, String branch) {
        return "{\"workspacePath\":\"" + workspacePath + "\",\"branch\":\"" + branch + "\"}";
    }

    private Map<String, Object> buildSwitchResult() {
        Map<String, Object> repo = new HashMap<>(8);
        repo.put("name", "svc-alpha");
        repo.put("created", true);
        Map<String, Object> result = new HashMap<>(8);
        result.put("worktreePath", "E:/repo/ws/.worktrees/u-ou_test/feature-test-branch");
        result.put("branch", BRANCH);
        result.put("repos", Collections.singletonList(repo));
        return result;
    }

    private Map<String, Object> buildBranchItem() {
        Map<String, Object> item = new HashMap<>(8);
        item.put("branch", "feature-test-branch");
        item.put("repoCount", 1);
        return item;
    }
}
