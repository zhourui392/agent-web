package com.example.agentweb.interfaces;

import com.example.agentweb.app.WorktreeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(path = "/api/worktree", produces = MediaType.APPLICATION_JSON_VALUE)
public class WorktreeController {

    private final WorktreeService worktreeService;

    public WorktreeController(WorktreeService worktreeService) {
        this.worktreeService = worktreeService;
    }

    @PostMapping("/switch")
    public Map<String, Object> switchBranch(@RequestBody Map<String, String> req)
            throws IOException, InterruptedException {
        String workspacePath = req.get("workspacePath");
        String branch = req.get("branch");
        if (workspacePath == null || branch == null) {
            throw new IllegalArgumentException("workspacePath and branch are required");
        }
        return worktreeService.switchBranch(workspacePath, branch);
    }

    @PostMapping("/update")
    public Map<String, Object> updateBranch(@RequestBody Map<String, String> req)
            throws IOException, InterruptedException {
        String workspacePath = req.get("workspacePath");
        String branch = req.get("branch");
        if (workspacePath == null || branch == null) {
            throw new IllegalArgumentException("workspacePath and branch are required");
        }
        return worktreeService.updateBranch(workspacePath, branch);
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam("workspacePath") String workspacePath)
            throws IOException {
        return worktreeService.listWorktrees(workspacePath);
    }

    @DeleteMapping("/remove")
    public Map<String, Object> remove(@RequestParam("workspacePath") String workspacePath,
                                      @RequestParam("branch") String branch)
            throws IOException, InterruptedException {
        worktreeService.removeWorktree(workspacePath, branch);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }
}
