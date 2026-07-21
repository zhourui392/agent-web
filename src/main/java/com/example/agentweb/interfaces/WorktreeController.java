package com.example.agentweb.interfaces;

import com.example.agentweb.app.WorktreeService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.worktree.BranchNameValidator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * worktree 分支隔离接口。每个操作按当前登录用户落到私有桶 {@code .worktrees/u-{userSlug}} +
 * 私有 ref {@code wt/{userSlug}/{branch}}, 多用户切同名分支互不冲突。
 *
 * @author zhourui(V33215020)
 */
@RestController
@RequestMapping(path = "/api/worktree", produces = MediaType.APPLICATION_JSON_VALUE)
public class WorktreeController {

    private final WorktreeService worktreeService;
    private final CurrentUserProvider currentUserProvider;

    public WorktreeController(WorktreeService worktreeService, CurrentUserProvider currentUserProvider) {
        this.worktreeService = worktreeService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/switch")
    public Map<String, Object> switchBranch(@RequestBody Map<String, String> req)
            throws IOException, InterruptedException {
        String workspacePath = req.get("workspacePath");
        String branch = req.get("branch");
        if (workspacePath == null || branch == null) {
            throw new IllegalArgumentException("workspacePath and branch are required");
        }
        String normalizedBranch = BranchNameValidator.validateAndNormalize(branch);
        return worktreeService.switchBranch(currentUserProvider.currentUserId(), workspacePath, normalizedBranch);
    }

    @PostMapping("/update")
    public Map<String, Object> updateBranch(@RequestBody Map<String, String> req)
            throws IOException, InterruptedException {
        String workspacePath = req.get("workspacePath");
        String branch = req.get("branch");
        if (workspacePath == null || branch == null) {
            throw new IllegalArgumentException("workspacePath and branch are required");
        }
        String normalizedBranch = BranchNameValidator.validateAndNormalize(branch);
        return worktreeService.updateBranch(currentUserProvider.currentUserId(), workspacePath, normalizedBranch);
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam("workspacePath") String workspacePath)
            throws IOException {
        return worktreeService.listWorktrees(currentUserProvider.currentUserId(), workspacePath);
    }

    @DeleteMapping("/remove")
    public Map<String, Object> remove(@RequestParam("workspacePath") String workspacePath,
                                      @RequestParam("branch") String branch)
            throws IOException, InterruptedException {
        String normalizedBranch = BranchNameValidator.validateAndNormalize(branch);
        worktreeService.removeWorktree(currentUserProvider.currentUserId(), workspacePath, normalizedBranch);
        Map<String, Object> result = new HashMap<>(16);
        result.put("success", true);
        return result;
    }
}
