package com.example.agentweb.interfaces;

import com.example.agentweb.app.worktree.WorktreeAppService;
import com.example.agentweb.app.worktree.WorktreeBranchView;
import com.example.agentweb.app.worktree.WorktreeRemovalView;
import com.example.agentweb.app.worktree.WorktreeSwitchView;
import com.example.agentweb.app.worktree.WorktreeUpdateView;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.worktree.BranchNameValidator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
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

    private final WorktreeAppService worktreeAppService;
    private final CurrentUserProvider currentUserProvider;

    public WorktreeController(WorktreeAppService worktreeAppService, CurrentUserProvider currentUserProvider) {
        this.worktreeAppService = worktreeAppService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/switch")
    public WorktreeSwitchView switchBranch(@RequestBody Map<String, String> req)
            throws IOException, InterruptedException {
        String workspacePath = req.get("workspacePath");
        String branch = req.get("branch");
        if (workspacePath == null || branch == null) {
            throw new IllegalArgumentException("workspacePath and branch are required");
        }
        String normalizedBranch = BranchNameValidator.validateAndNormalize(branch);
        return worktreeAppService.switchBranch(currentUserProvider.currentUserId(), workspacePath, normalizedBranch);
    }

    @PostMapping("/update")
    public WorktreeUpdateView updateBranch(@RequestBody Map<String, String> req)
            throws IOException, InterruptedException {
        String workspacePath = req.get("workspacePath");
        String branch = req.get("branch");
        if (workspacePath == null || branch == null) {
            throw new IllegalArgumentException("workspacePath and branch are required");
        }
        String normalizedBranch = BranchNameValidator.validateAndNormalize(branch);
        return worktreeAppService.updateBranch(currentUserProvider.currentUserId(), workspacePath, normalizedBranch);
    }

    @GetMapping("/list")
    public List<WorktreeBranchView> list(@RequestParam("workspacePath") String workspacePath)
            throws IOException {
        return worktreeAppService.listWorktrees(currentUserProvider.currentUserId(), workspacePath);
    }

    @DeleteMapping("/remove")
    public WorktreeRemovalView remove(@RequestParam("workspacePath") String workspacePath,
                                      @RequestParam("branch") String branch)
            throws IOException, InterruptedException {
        String normalizedBranch = BranchNameValidator.validateAndNormalize(branch);
        return worktreeAppService.removeWorktree(
                currentUserProvider.currentUserId(), workspacePath, normalizedBranch);
    }
}
