package com.example.agentweb.domain.worktree;

/**
 * 每用户私有分支引用 VO。
 *
 * <p>共享对象库下, 同名逻辑分支(如 {@code feature/login})只能在一个 worktree 检出, 多用户同时切换会撞
 * {@code fatal: 'feature/login' is already checked out}。本 VO 把逻辑分支命名空间化为
 * {@code wt/{userSlug}/{logicalBranch}}, 使每个用户在同一对象库各持一条私有 ref, checkout 互不冲突。</p>
 *
 * <p>不复用 {@code BranchNameValidator}: 逻辑分支可能是 fallback 的默认分支(如 {@code master}/{@code main}),
 * 它们合法但不满足 release/feature 等关键字前缀约束; 关键字校验只在 Controller 对用户输入做。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public final class UserBranchRef {

    /** 私有分支统一前缀, 与远端裸分支命名空间隔离。 */
    public static final String PREFIX = "wt/";

    private final String userSlug;
    private final String logicalBranch;

    private UserBranchRef(String userSlug, String logicalBranch) {
        this.userSlug = userSlug;
        this.logicalBranch = logicalBranch;
    }

    /**
     * 构造私有分支引用。
     *
     * @param userSlug      文件系统安全的用户 slug(见 {@link UserSlug})
     * @param logicalBranch 逻辑分支名(可含 {@code /}, 如 feature/login)
     * @return 私有分支引用
     * @throws IllegalArgumentException 任一参数为空时
     */
    public static UserBranchRef of(String userSlug, String logicalBranch) {
        if (userSlug == null || userSlug.trim().isEmpty()) {
            throw new IllegalArgumentException("userSlug 不能为空");
        }
        if (logicalBranch == null || logicalBranch.trim().isEmpty()) {
            throw new IllegalArgumentException("logicalBranch 不能为空");
        }
        return new UserBranchRef(userSlug.trim(), logicalBranch.trim());
    }

    /** @return 命名空间化的私有本地分支名 {@code wt/{userSlug}/{logicalBranch}} */
    public String namespacedRef() {
        return PREFIX + userSlug + "/" + logicalBranch;
    }

    public String userSlug() {
        return userSlug;
    }

    public String logicalBranch() {
        return logicalBranch;
    }

    /**
     * 从命名空间化 ref 反解逻辑分支名。
     *
     * @param ref {@code wt/{userSlug}/{logicalBranch}} 形式的私有 ref
     * @return 逻辑分支名(可含 {@code /}, 如 feature/login)
     * @throws IllegalArgumentException ref 不是合法私有 ref 时
     */
    public static String logicalBranchOf(String ref) {
        if (ref == null || !ref.startsWith(PREFIX)) {
            throw new IllegalArgumentException("非法私有 ref: " + ref);
        }
        String afterPrefix = ref.substring(PREFIX.length());
        int firstSlash = afterPrefix.indexOf('/');
        if (firstSlash < 0 || firstSlash == afterPrefix.length() - 1) {
            throw new IllegalArgumentException("非法私有 ref: " + ref);
        }
        return afterPrefix.substring(firstSlash + 1);
    }
}
