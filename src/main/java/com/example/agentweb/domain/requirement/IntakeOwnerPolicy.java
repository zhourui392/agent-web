package com.example.agentweb.domain.requirement;

import java.util.function.Predicate;

/**
 * 外部接入（GITLAB_ISSUE / REST）的 owner 映射策略：优先使用 GitLab username 作为登录用户 ID，
 * 作者缺失或不在用户目录时回落配置的接待人；回落人也不可用则拒收（不建无主需求）。
 * 目录谓词由调用方注入（目录不可用时应 fail-open 恒真，校验是软闸不得卡接入）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public final class IntakeOwnerPolicy {

    /**
     * 解析需求属主（不校验目录，等价于恒真谓词）。
     *
     * @param authorUsername issue 作者 GitLab username（可空）
     * @param fallbackOwner  回落接待人（agent.requirement.intake.default-owner，可空）
     * @return 属主工号
     */
    public String resolveOwner(String authorUsername, String fallbackOwner) {
        return resolveOwner(authorUsername, fallbackOwner, user -> true);
    }

    /**
     * 带用户目录校验的解析：作者存在且在目录 → 作者；否则接待人存在且在目录 → 接待人；否则拒收。
     *
     * @param authorUsername issue 作者 GitLab username（可空）
     * @param fallbackOwner  回落接待人（可空）
     * @param knownUser      目录存在性谓词（目录不可用时调用方保证 fail-open）
     * @return 属主工号
     */
    public String resolveOwner(String authorUsername, String fallbackOwner, Predicate<String> knownUser) {
        if (hasText(authorUsername) && knownUser.test(authorUsername.trim())) {
            return authorUsername.trim();
        }
        if (hasText(fallbackOwner) && knownUser.test(fallbackOwner.trim())) {
            return fallbackOwner.trim();
        }
        throw new OwnerUnresolvedException();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
