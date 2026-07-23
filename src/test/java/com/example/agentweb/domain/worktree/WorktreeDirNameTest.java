package com.example.agentweb.domain.worktree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link WorktreeDirName} 领域规则单测: 净化、目录穿越防线、桶内解析。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class WorktreeDirNameTest {

    @Test
    @DisplayName("斜杠与特殊字符全部替换为 -")
    void fromBranch_sanitizesUnsafeChars() {
        assertEquals("feature-deep-nested", WorktreeDirName.fromBranch("feature/deep/nested").value());
        assertEquals("release-v1.0", WorktreeDirName.fromBranch("release/v1.0").value());
        assertEquals("a-b-c", WorktreeDirName.fromBranch("a:b\\c").value());
    }

    @Test
    @DisplayName("合法字符原样保留")
    void fromBranch_keepsSafeChars() {
        assertEquals("feature_x.y-z", WorktreeDirName.fromBranch("feature_x.y-z").value());
    }

    @Test
    @DisplayName("整段为 . / .. 或空串 → 拒绝")
    void fromBranch_rejectsTraversalSegments() {
        assertThrows(IllegalArgumentException.class, () -> WorktreeDirName.fromBranch(".."));
        assertThrows(IllegalArgumentException.class, () -> WorktreeDirName.fromBranch("."));
        assertThrows(IllegalArgumentException.class, () -> WorktreeDirName.fromBranch(""));
    }

    @Test
    @DisplayName("纯分隔符净化为 - 序列, 不构成穿越段, 放行")
    void fromBranch_slashesBecomeDashes() {
        assertEquals("---", WorktreeDirName.fromBranch("///").value());
    }

    @Test
    @DisplayName("resolveWithin: 解析结果必须在桶内")
    void resolveWithin_staysInsideBucket() {
        Path bucket = Paths.get("/ws/.worktrees/u-x");
        Path resolved = WorktreeDirName.fromBranch("feature/login").resolveWithin(bucket);
        assertEquals(bucket.resolve("feature-login"), resolved);
    }
}
