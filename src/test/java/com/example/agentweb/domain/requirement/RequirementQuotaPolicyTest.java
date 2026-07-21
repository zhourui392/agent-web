package com.example.agentweb.domain.requirement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 配额策略边界测试：等于上限拒、上限-1 放行、非正上限不设限。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RequirementQuotaPolicyTest {

    private final RequirementQuotaPolicy policy = new RequirementQuotaPolicy();

    @Test
    public void active_count_below_limit_should_pass() {
        assertDoesNotThrow(() -> policy.assertWithinActiveQuota("u1", 4, 5));
    }

    @Test
    public void active_count_at_limit_should_reject() {
        assertThrows(RequirementQuotaExceededException.class,
                () -> policy.assertWithinActiveQuota("u1", 5, 5));
        assertThrows(RequirementQuotaExceededException.class,
                () -> policy.assertWithinActiveQuota("u1", 6, 5));
    }

    @Test
    public void non_positive_limit_means_unlimited() {
        assertDoesNotThrow(() -> policy.assertWithinActiveQuota("u1", 100, 0));
        assertDoesNotThrow(() -> policy.assertWithinActiveQuota("u1", 100, -1));
    }
}
