package com.example.agentweb.domain.worktree;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Worktree} 聚合根单测：构造不变量与私有 ref 回收边界。
 *
 * @author alex
 * @since 2026-07-23
 */
class WorktreeTest {

    @Test
    void restore_validState_shouldBuildStableIdentity() {
        Worktree worktree = Worktree.restore("alice", "svc-a", "feature/login",
                Paths.get("/workspace/.worktrees/u-alice/feature-login/svc-a"),
                "wt/alice/feature/login", 2);

        assertEquals("alice:svc-a:feature/login", worktree.id());
        assertEquals("alice", worktree.userSlug());
        assertEquals("svc-a", worktree.repoName());
        assertEquals("feature/login", worktree.branch());
        assertEquals(2, worktree.repoLeafCount());
    }

    @Test
    void restore_missingIdentityOrPath_shouldReject() {
        assertThrows(IllegalArgumentException.class, () -> Worktree.restore(
                "", "svc-a", "feature/login", Paths.get("/tmp/wt"), "wt/alice/feature/login", 1));
        assertThrows(IllegalArgumentException.class, () -> Worktree.restore(
                "alice", "", "feature/login", Paths.get("/tmp/wt"), "wt/alice/feature/login", 1));
        assertThrows(IllegalArgumentException.class, () -> Worktree.restore(
                "alice", "svc-a", "", Paths.get("/tmp/wt"), "wt/alice/feature/login", 1));
        assertThrows(IllegalArgumentException.class, () -> Worktree.restore(
                "alice", "svc-a", "feature/login", null, "wt/alice/feature/login", 1));
        assertThrows(IllegalArgumentException.class, () -> Worktree.restore(
                "alice", "svc-a", "feature/login", Paths.get("/tmp/wt"), "wt/alice/feature/login", -1));
    }

    @Test
    void removablePrivateRef_ownedRef_shouldReturnRef() {
        Worktree worktree = Worktree.restore("alice", "svc-a", "feature/login",
                Paths.get("/tmp/wt"), "wt/alice/feature/login", 1);

        assertEquals("wt/alice/feature/login", worktree.requireRemovable());
        assertEquals("wt/alice/feature/login", worktree.removablePrivateRef().orElse(null));
    }

    @Test
    void removablePrivateRef_realBusinessBranch_shouldSkipRecycle() {
        Worktree worktree = Worktree.restore("alice", "svc-a", "feature/login",
                Paths.get("/tmp/wt"), "feature/login", 1);

        assertFalse(worktree.removablePrivateRef().isPresent());
        assertThrows(IllegalStateException.class, worktree::requireRemovable);
    }

    @Test
    void removablePrivateRef_otherUsersPrivateRef_shouldFailClosed() {
        Worktree worktree = Worktree.restore("alice", "svc-a", "feature/login",
                Paths.get("/tmp/wt"), "wt/bob/feature/login", 1);

        assertThrows(IllegalStateException.class, worktree::removablePrivateRef);
        assertThrows(IllegalStateException.class, worktree::requireRemovable);
    }
}
