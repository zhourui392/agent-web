package com.example.agentweb.domain.requirement;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 状态迁移合法性表（detailed-design §1.2 迁移表 T1—T15 的数据化）。
 * 聚合的每个迁移方法先查此表再执行守卫——15 条迁移的合法性判断收敛为一次查表，压聚合行数。
 *
 * <p>SUSPENDED 不允许再 SUSPEND：重复挂起会覆盖 statusBeforeSuspend 导致 resume 丢失原状态。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public final class RequirementTransitions {

    private static final Map<RequirementStatus, Set<RequirementAction>> ALLOWED = buildTable();

    private RequirementTransitions() {
    }

    private static Map<RequirementStatus, Set<RequirementAction>> buildTable() {
        Map<RequirementStatus, Set<RequirementAction>> table = new EnumMap<>(RequirementStatus.class);
        table.put(RequirementStatus.INTAKE, EnumSet.of(
                RequirementAction.ATTACH_PLAN, RequirementAction.SUSPEND, RequirementAction.ARCHIVE));
        table.put(RequirementStatus.PLANNED, EnumSet.of(
                RequirementAction.ATTACH_PLAN, RequirementAction.REJECT_PLAN, RequirementAction.APPROVE,
                RequirementAction.SUSPEND, RequirementAction.ARCHIVE));
        table.put(RequirementStatus.APPROVED, EnumSet.of(
                RequirementAction.ATTACH_WORKSPACE, RequirementAction.START_IMPLEMENT,
                RequirementAction.SUSPEND, RequirementAction.ARCHIVE));
        table.put(RequirementStatus.IMPLEMENTING, EnumSet.of(
                RequirementAction.START_FIX, RequirementAction.START_VERIFY,
                RequirementAction.SUSPEND, RequirementAction.ARCHIVE));
        table.put(RequirementStatus.VERIFYING, EnumSet.of(
                RequirementAction.APPLY_VERIFICATION_OUTCOME, RequirementAction.SUSPEND,
                RequirementAction.ARCHIVE));
        table.put(RequirementStatus.REVIEW, EnumSet.of(
                RequirementAction.MARK_DELIVERED, RequirementAction.REQUEST_CHANGES,
                RequirementAction.SUSPEND, RequirementAction.ARCHIVE));
        table.put(RequirementStatus.SUSPENDED, EnumSet.of(
                RequirementAction.RESUME, RequirementAction.ARCHIVE));
        table.put(RequirementStatus.DELIVERED, EnumSet.noneOf(RequirementAction.class));
        table.put(RequirementStatus.ARCHIVED, EnumSet.noneOf(RequirementAction.class));
        return Collections.unmodifiableMap(table);
    }

    public static boolean isAllowed(RequirementStatus from, RequirementAction action) {
        return ALLOWED.get(from).contains(action);
    }

    /**
     * 迁移合法性守卫：非法组合抛异常，聚合迁移方法的统一第一步。
     *
     * @param from   当前状态
     * @param action 待执行动作
     */
    public static void assertAllowed(RequirementStatus from, RequirementAction action) {
        if (!isAllowed(from, action)) {
            throw new IllegalRequirementTransitionException(from, action);
        }
    }
}
