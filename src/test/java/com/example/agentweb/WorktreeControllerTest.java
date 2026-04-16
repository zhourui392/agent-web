package com.example.agentweb;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorktreeControllerTest {

    @Autowired
    private MockMvc mvc;

    @TempDir
    static Path tempDir;

    private static String workspacePath;

    @BeforeAll
    static void initWorkspace() throws Exception {
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        workspacePath = workspace.toString();

        // Create a git repo with a branch
        Path repo = workspace.resolve("svc-alpha");
        Files.createDirectories(repo);
        gitExec(repo, "init");
        gitExec(repo, "config", "user.email", "test@test.com");
        gitExec(repo, "config", "user.name", "test");
        Files.write(repo.resolve("README.md"), "# svc-alpha".getBytes());
        gitExec(repo, "add", ".");
        gitExec(repo, "commit", "-m", "init");
        gitExec(repo, "branch", "test-branch");
    }

    /** 转义 JSON 字符串中的反斜杠和双引号（Windows 路径含反斜杠时必需）。 */
    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void gitExec(Path dir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (r.readLine() != null) {}
        }
        if (p.waitFor() != 0) {
            throw new RuntimeException("git failed: " + String.join(" ", cmd));
        }
    }

    @Test
    @DisplayName("POST /api/worktree/switch 创建 worktree 并返回结果")
    void switchBranch_returns200WithWorktreePath() throws Exception {
        String body = "{\"workspacePath\":\"" + jsonEscape(workspacePath) + "\",\"branch\":\"test-branch\"}";

        mvc.perform(post("/api/worktree/switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worktreePath").isNotEmpty())
                .andExpect(jsonPath("$.branch").value("test-branch"))
                .andExpect(jsonPath("$.repos").isArray())
                .andExpect(jsonPath("$.repos[0].name").value("svc-alpha"))
                .andExpect(jsonPath("$.repos[0].created").value(true));
    }

    @Test
    @DisplayName("GET /api/worktree/list 列出已创建的 worktree")
    void listWorktrees_returnsCreatedBranches() throws Exception {
        // Ensure worktree exists
        String body = "{\"workspacePath\":\"" + jsonEscape(workspacePath) + "\",\"branch\":\"test-branch\"}";
        mvc.perform(post("/api/worktree/switch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        mvc.perform(get("/api/worktree/list")
                        .param("workspacePath", workspacePath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].branch").value("test-branch"))
                .andExpect(jsonPath("$[0].repoCount").value(1));
    }

    @Test
    @DisplayName("DELETE /api/worktree/remove 删除指定分支的 worktree")
    void removeWorktree_deletesAndReturnsSuccess() throws Exception {
        // Create first
        String body = "{\"workspacePath\":\"" + jsonEscape(workspacePath) + "\",\"branch\":\"test-branch\"}";
        mvc.perform(post("/api/worktree/switch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        // Remove
        mvc.perform(delete("/api/worktree/remove")
                        .param("workspacePath", workspacePath)
                        .param("branch", "test-branch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Verify list is empty
        mvc.perform(get("/api/worktree/list")
                        .param("workspacePath", workspacePath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("POST /api/worktree/switch 无效路径返回 400")
    void switchBranch_invalidPath_returns400() throws Exception {
        String body = "{\"workspacePath\":\"/nonexistent/path\",\"branch\":\"master\"}";

        mvc.perform(post("/api/worktree/switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
