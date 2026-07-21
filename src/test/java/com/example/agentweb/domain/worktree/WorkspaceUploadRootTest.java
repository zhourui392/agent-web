package com.example.agentweb.domain.worktree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain 单测: {@link WorkspaceUploadRoot} 把「短命 worktree 工作目录」上提到「稳定 workspace 根」的规则。
 * 零容器、零 Mock。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-17
 */
class WorkspaceUploadRootTest {

    @Test
    @DisplayName("worktree 根路径 → 上提到 .worktrees 之上的 workspace 根")
    void worktreeBase_liftsToWorkspaceRoot() {
        String workingDir = "/home/service/workspace/.worktrees/u-Q600096-6ba95f74ed05/feature-20260630-push";

        assertEquals(Paths.get("/home/service/workspace").toString(), WorkspaceUploadRoot.resolve(workingDir));
    }

    @Test
    @DisplayName("worktree 内更深子仓路径 → 仍上提到同一 workspace 根")
    void deeperRepoInsideWorktree_liftsToSameRoot() {
        String workingDir = "/home/service/workspace/.worktrees/u-x/branch/platform-server/repoA";

        assertEquals(Paths.get("/home/service/workspace").toString(), WorkspaceUploadRoot.resolve(workingDir));
    }

    @Test
    @DisplayName("非 worktree 路径 → 原样返回(稳定目录,无需上提)")
    void nonWorktreePath_returnedUnchanged() {
        String workingDir = "/home/service/workspace/platform-server/repoA";

        assertEquals(workingDir, WorkspaceUploadRoot.resolve(workingDir));
    }

    @Test
    @DisplayName("含 .. / 尾斜杠的 worktree 路径先 normalize 再上提")
    void worktreePathWithDotDot_isNormalizedBeforeLift() {
        String workingDir = "/home/service/workspace/.worktrees/u-x/../u-x/branch/";

        assertEquals(Paths.get("/home/service/workspace").toString(), WorkspaceUploadRoot.resolve(workingDir));
    }

    @Test
    @DisplayName(".worktrees 作为直接子目录 → 解析到其父 workspace 根")
    void worktreesAsImmediateChild_resolvesToParent() {
        String workingDir = "/home/service/workspace/.worktrees/u-x/branch";

        String resolved = WorkspaceUploadRoot.resolve(workingDir);

        assertEquals(Paths.get("/home/service/workspace").toString(), resolved);
    }

    @Test
    @DisplayName("null / 空白 → 原样返回(调用方已前置校验,此处仅防御)")
    void blankInput_returnedAsIs() {
        assertEquals(null, WorkspaceUploadRoot.resolve(null));
        assertEquals("", WorkspaceUploadRoot.resolve(""));
        assertEquals("   ", WorkspaceUploadRoot.resolve("   "));
    }

    @Test
    @DisplayName(".worktrees 作为首段(其上无 workspace 根) → 防御性原样返回")
    void worktreesAtFirstSegment_returnedUnchanged() {
        String workingDir = "/.worktrees/u-x/branch";

        assertEquals(workingDir, WorkspaceUploadRoot.resolve(workingDir));
    }
}
