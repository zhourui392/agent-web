package com.example.agentweb.domain.worktree;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 把用户标识(飞书 union_id 或登录名)归一化为文件系统安全的目录名片段,
 * 用于 worktree 物理隔离桶 {@code .worktrees/u-{slug}/...}。
 *
 * <p>规则: 未登录(null/空)归入固定公共桶 {@link #LOCAL_BUCKET}; 已登录用户取
 * "净化前缀 + SHA-256 短哈希后缀"。哈希后缀保证不同 union_id 即便净化后前缀相同, 也不会塌缩到同一桶;
 * 同时真实用户恒带后缀, 永不与公共桶 {@code _local} 碰撞。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public final class UserSlug {

    /** 未登录 / 无主会话的固定公共桶名。真实用户恒带哈希后缀, 不会与之碰撞。 */
    public static final String LOCAL_BUCKET = "_local";

    /** 净化前缀最大长度, 配合短哈希控制 slug 总长(利于 Windows 路径长度)。 */
    private static final int MAX_PREFIX_LENGTH = 16;
    /** SHA-256 摘要取前几位十六进制作为后缀, 12 位即 48bit, 把生日碰撞界推到约 1600 万用户量级。 */
    private static final int HASH_HEX_LENGTH = 12;
    /** 文件系统不安全字符: 非字母数字下划线连字符一律替换。 */
    private static final String UNSAFE_CHARS = "[^a-zA-Z0-9_-]";

    private UserSlug() {
    }

    /**
     * 归一化用户标识为文件系统安全 slug。
     *
     * @param userId 用户标识, null/空表示未登录
     * @return 文件系统安全的 slug; 未登录返回 {@link #LOCAL_BUCKET}
     */
    public static String slug(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return LOCAL_BUCKET;
        }
        String raw = userId.trim();
        String prefix = raw.replaceAll(UNSAFE_CHARS, "-");
        if (prefix.length() > MAX_PREFIX_LENGTH) {
            prefix = prefix.substring(0, MAX_PREFIX_LENGTH);
        }
        return prefix + "-" + shortHash(raw);
    }

    private static String shortHash(String raw) {
        byte[] digest = sha256(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(HASH_HEX_LENGTH + 2);
        for (byte b : digest) {
            if (hex.length() >= HASH_HEX_LENGTH) {
                break;
            }
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.substring(0, HASH_HEX_LENGTH);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JLS 强制内置算法, 运行期不可能缺失
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
