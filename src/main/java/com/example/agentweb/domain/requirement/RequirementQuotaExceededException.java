package com.example.agentweb.domain.requirement;

import lombok.Getter;

/**
 * 活跃需求配额超限。映射为 409 + QUOTA_EXCEEDED（关闭旧需求后可重试）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Getter
public class RequirementQuotaExceededException extends RuntimeException {

    private final int activeCount;
    private final int maxActivePerUser;

    public RequirementQuotaExceededException(String owner, int activeCount, int maxActivePerUser) {
        super("active requirement quota exceeded: owner=" + owner
                + ", active=" + activeCount + ", max=" + maxActivePerUser);
        this.activeCount = activeCount;
        this.maxActivePerUser = maxActivePerUser;
    }
}
