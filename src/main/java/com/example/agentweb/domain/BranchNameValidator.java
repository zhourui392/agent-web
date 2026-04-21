package com.example.agentweb.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 分支命名规范校验。
 * <p>用户输入的分支名必须以约定关键字开头：{@code release} / {@code hotfix} / {@code feature}
 * / {@code cr} / {@code bugfix}；关键字后可直接跟分支主体（如 {@code release20260402}），
 * 也可用 {@code /} 或 {@code -} 作为分隔符（如 {@code release/20260402-version} /
 * {@code release-20260402-version}），避免 worktree 下出现 {@code master} / 临时乱名分支。</p>
 */
public final class BranchNameValidator {

    /** 允许的分支关键字。新增规范时改这里即可。 */
    public static final List<String> ALLOWED_KEYWORDS = Collections.unmodifiableList(Arrays.asList(
            "release",
            "hotfix",
            "feature",
            "cr",
            "bugfix"
    ));

    private BranchNameValidator() { }

    /**
     * 校验并归一化用户输入的分支名：去除前后空白，校验以允许关键字开头。
     *
     * @return 去空白后的规范分支名
     * @throws IllegalArgumentException 分支名为空或不以允许关键字开头时
     */
    public static String validateAndNormalize(String rawBranch) {
        if (rawBranch == null) {
            throw new IllegalArgumentException("分支名不能为空");
        }
        String trimmed = rawBranch.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("分支名不能为空");
        }
        for (String keyword : ALLOWED_KEYWORDS) {
            if (trimmed.startsWith(keyword) && trimmed.length() > keyword.length()) {
                return trimmed;
            }
        }
        throw new IllegalArgumentException("分支名必须以 "
                + String.join(" / ", ALLOWED_KEYWORDS) + " 之一开头: " + trimmed);
    }
}
