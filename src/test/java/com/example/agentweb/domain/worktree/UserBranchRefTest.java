package com.example.agentweb.domain.worktree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Domain 单测: {@link UserBranchRef} 命名空间化与反解。零容器、零 Mock。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
class UserBranchRefTest {

    @Test
    @DisplayName("namespacedRef 拼为 wt/{slug}/{logical}")
    void namespacedRef_composesPrefix() {
        UserBranchRef ref = UserBranchRef.of("ou_abc-1a2b3c4d", "feature/login");

        assertEquals("wt/ou_abc-1a2b3c4d/feature/login", ref.namespacedRef());
        assertEquals("ou_abc-1a2b3c4d", ref.userSlug());
        assertEquals("feature/login", ref.logicalBranch());
    }

    @Test
    @DisplayName("logicalBranchOf 反解 namespacedRef 往返一致")
    void roundtrip_logicalBranch() {
        String namespaced = UserBranchRef.of("u-x", "feature/login").namespacedRef();

        assertEquals("feature/login", UserBranchRef.logicalBranchOf(namespaced));
    }

    @Test
    @DisplayName("逻辑分支含多层斜杠时反解保留完整路径")
    void logicalBranchOf_keepsNestedSlashes() {
        assertEquals("feature/deep/nested",
                UserBranchRef.logicalBranchOf("wt/ou_abc-123/feature/deep/nested"));
    }

    @Test
    @DisplayName("fallback 默认分支 master 合法(不做关键字前缀校验)")
    void allowsDefaultBranchNames() {
        assertEquals("wt/u-x/master", UserBranchRef.of("u-x", "master").namespacedRef());
        assertEquals("wt/u-x/main", UserBranchRef.of("u-x", "main").namespacedRef());
    }

    @Test
    @DisplayName("of 空参数抛 IllegalArgumentException")
    void of_rejectsBlankArgs() {
        assertThrows(IllegalArgumentException.class, () -> UserBranchRef.of(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> UserBranchRef.of("", "x"));
        assertThrows(IllegalArgumentException.class, () -> UserBranchRef.of("s", null));
        assertThrows(IllegalArgumentException.class, () -> UserBranchRef.of("s", "  "));
    }

    @Test
    @DisplayName("logicalBranchOf 非法 ref 抛 IllegalArgumentException")
    void logicalBranchOf_rejectsIllegalRef() {
        assertThrows(IllegalArgumentException.class, () -> UserBranchRef.logicalBranchOf(null));
        assertThrows(IllegalArgumentException.class, () -> UserBranchRef.logicalBranchOf("master"));
        assertThrows(IllegalArgumentException.class, () -> UserBranchRef.logicalBranchOf("wt/onlyslug"));
        assertThrows(IllegalArgumentException.class, () -> UserBranchRef.logicalBranchOf("wt/slug/"));
    }
}
