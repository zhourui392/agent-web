package com.example.agentweb.domain.worktree;

import java.nio.file.Path;

/**
 * 分支名对应的文件系统安全目录名。
 *
 * <p>逻辑分支名(如 {@code feature/login})含路径分隔符等文件系统不安全字符, 不能直接作目录名。
 * 本 VO 收口"分支名 → worktree 桶内目录名"的净化规则与目录穿越防线:
 * 不安全字符一律替换为 {@code -}; 净化结果为空 / {@code .} / {@code ..} 直接拒绝;
 * {@link #resolveWithin(Path)} 解析后再断言不逃逸出桶(纵深防御, 即便净化规则有漏网也兜住)。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public final class WorktreeDirName {

    private static final String UNSAFE_CHARS = "[^a-zA-Z0-9._-]";

    private final String value;

    private WorktreeDirName(String value) {
        this.value = value;
    }

    /**
     * 由逻辑分支名构造安全目录名。
     *
     * @param branch 逻辑分支名(可含 {@code /})
     * @return 安全目录名
     * @throws IllegalArgumentException 净化结果为空或为 {@code .}/{@code ..} 时
     */
    public static WorktreeDirName fromBranch(String branch) {
        String safe = branch.replaceAll(UNSAFE_CHARS, "-");
        // 防目录穿越: 整段为 "." / ".." 会让 resolve 跳出用户桶, 直接拒绝(分隔符已被替换为 -, 故只需挡这两种)
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) {
            throw new IllegalArgumentException("Illegal branch name: " + branch);
        }
        return new WorktreeDirName(safe);
    }

    /**
     * 在桶目录下解析本目录名并断言不逃逸出桶。
     *
     * @param bucket 用户桶目录
     * @return 解析后的分支目录
     * @throws IllegalArgumentException 解析结果跳出桶时
     */
    public Path resolveWithin(Path bucket) {
        Path bucketRoot = bucket.normalize();
        Path resolved = bucketRoot.resolve(value).normalize();
        if (!resolved.startsWith(bucketRoot)) {
            throw new IllegalArgumentException("Branch path escapes bucket: " + value);
        }
        return resolved;
    }

    /** @return 净化后的目录名片段 */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
