package com.example.agentweb.domain.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工件缺失时的退出码兜底映射（detailed-design §4.3 降级规则）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class VerificationOutcomeTest {

    @Test
    public void fallback_should_map_nonzero_exit_to_deploy_failed() {
        assertEquals(VerificationOutcome.DEPLOY_FAILED, VerificationOutcome.fallbackForExit(1));
        assertEquals(VerificationOutcome.DEPLOY_FAILED, VerificationOutcome.fallbackForExit(-1));
        assertEquals(VerificationOutcome.DEPLOY_FAILED, VerificationOutcome.fallbackForExit(66));
    }

    @Test
    public void fallback_should_map_zero_exit_without_evidence_to_blocked() {
        // 退出 0 但无 .flowstate 证据 ≠ 验证通过——降级为 BLOCKED 交人工确认,绝不自动放行
        assertEquals(VerificationOutcome.BLOCKED, VerificationOutcome.fallbackForExit(0));
    }
}
