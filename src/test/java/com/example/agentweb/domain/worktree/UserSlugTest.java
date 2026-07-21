package com.example.agentweb.domain.worktree;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain 单测: {@link UserSlug} 归一化规则。零容器、零 Mock。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
class UserSlugTest {

    @Test
    @DisplayName("null / 空 / 纯空白 → 公共桶 _local")
    void blankUserId_mapsToLocalBucket() {
        assertEquals(UserSlug.LOCAL_BUCKET, UserSlug.slug(null));
        assertEquals(UserSlug.LOCAL_BUCKET, UserSlug.slug(""));
        assertEquals(UserSlug.LOCAL_BUCKET, UserSlug.slug("   "));
    }

    @Test
    @DisplayName("普通 union_id → 净化前缀 + '-' + 12 位哈希后缀")
    void unionId_keepsPrefixAndAppendsHash() {
        String slug = UserSlug.slug("ou_abc123");

        assertTrue(slug.startsWith("ou_abc123-"), "应保留可读前缀: " + slug);
        String suffix = slug.substring("ou_abc123-".length());
        assertEquals(12, suffix.length(), "哈希后缀应为 12 位十六进制: " + slug);
        assertTrue(suffix.matches("[0-9a-f]{12}"), "后缀应为十六进制: " + suffix);
    }

    @Test
    @DisplayName("文件系统不安全字符全部替换为 -")
    void unsafeChars_sanitized() {
        String slug = UserSlug.slug("a/b:c\\d e");

        assertFalse(slug.contains("/"), slug);
        assertFalse(slug.contains(":"), slug);
        assertFalse(slug.contains("\\"), slug);
        assertFalse(slug.contains(" "), slug);
        assertTrue(slug.matches("[a-zA-Z0-9_-]+"), "整体应文件系统安全: " + slug);
    }

    @Test
    @DisplayName("净化后前缀相同的不同 id, 靠哈希后缀避免塌缩到同一桶")
    void differentIds_collapsingToSamePrefix_stayDistinct() {
        // "user/1" 与 "user:1" 净化后前缀都是 "user-1", 哈希必须把它们区分开
        String a = UserSlug.slug("user/1");
        String b = UserSlug.slug("user:1");

        assertTrue(a.startsWith("user-1-"), a);
        assertTrue(b.startsWith("user-1-"), b);
        assertNotEquals(a, b, "不同原始 id 不能塌缩到同一 slug");
    }

    @Test
    @DisplayName("同一 id 多次调用结果稳定(确定性哈希)")
    void sameId_isDeterministic() {
        assertEquals(UserSlug.slug("ou_stable"), UserSlug.slug("ou_stable"));
    }

    @Test
    @DisplayName("真实用户恒带哈希后缀, 永不与公共桶 _local 碰撞")
    void realUserNamedLocal_doesNotCollideWithBucket() {
        String slug = UserSlug.slug("_local");

        assertNotEquals(UserSlug.LOCAL_BUCKET, slug);
        assertTrue(slug.startsWith("_local-"), slug);
    }

    @Test
    @DisplayName("超长 id 前缀截断, 总长受控")
    void longId_prefixTruncated() {
        String slug = UserSlug.slug("ou_0123456789abcdefghijklmnopqrstuvwxyz");

        // 前缀截到 16 + '-' + 12 哈希 = 29
        assertEquals(29, slug.length(), slug);
    }

    @Test
    @DisplayName("trim 前后空白后再归一化")
    void trimsWhitespaceBeforeSlug() {
        assertEquals(UserSlug.slug("ou_x"), UserSlug.slug("  ou_x  "));
    }
}
