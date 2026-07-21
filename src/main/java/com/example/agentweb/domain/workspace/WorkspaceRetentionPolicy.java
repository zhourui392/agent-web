package com.example.agentweb.domain.workspace;

import com.example.agentweb.domain.requirement.RequirementStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * TTL 清理资格策略：REVIEW / VERIFYING / SUSPENDED 态需求的工作区不参与 TTL 清理——
 * 评审等待 / 验证中 / 人工接管现场，清了 T11 退回后无家可归（T5 只许 APPROVED 挂工作区，
 * 无法重新供给）；其余态按 TTL 判据走（detailed-design §2.1）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class WorkspaceRetentionPolicy {

    private static final Set<RequirementStatus> RETAINED_STATUSES = EnumSet.of(
            RequirementStatus.REVIEW, RequirementStatus.VERIFYING, RequirementStatus.SUSPENDED);

    /**
     * @param requirementStatus 工作区所属需求的当前状态
     * @return true = 允许进入 TTL 清理流程
     */
    public boolean eligibleForCleanup(RequirementStatus requirementStatus) {
        return !RETAINED_STATUSES.contains(requirementStatus);
    }
}
