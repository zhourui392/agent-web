package com.example.agentweb.domain.workspace;

import com.example.agentweb.domain.requirement.RequirementStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TTL 清理资格：REVIEW/VERIFYING/SUSPENDED 拒清理（评审等待/验证中/人工接管现场，
 * 清了 T11 退回后无法重挂——T5 只许 APPROVED 挂工作区），其余态放行（detailed-design §2.1）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class WorkspaceRetentionPolicyTest {

    private static final Set<RequirementStatus> RETAINED = EnumSet.of(
            RequirementStatus.REVIEW, RequirementStatus.VERIFYING, RequirementStatus.SUSPENDED);

    private final WorkspaceRetentionPolicy policy = new WorkspaceRetentionPolicy();

    @Test
    public void retained_statuses_should_block_cleanup() {
        for (RequirementStatus status : RETAINED) {
            assertFalse(policy.eligibleForCleanup(status), "应保留: " + status);
        }
    }

    @Test
    public void all_other_statuses_should_allow_cleanup() {
        for (RequirementStatus status : RequirementStatus.values()) {
            if (!RETAINED.contains(status)) {
                assertTrue(policy.eligibleForCleanup(status), "应放行: " + status);
            }
        }
    }
}
