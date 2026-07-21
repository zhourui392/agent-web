package com.example.agentweb.domain.requirement;

/**
 * 需求配额策略（含业务判断收 domain，计数由 app 从读侧取来传入）。
 * M0 启用每用户活跃需求数一维；每需求并发 run 数（M2 计划门起）届时扩展本类。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RequirementQuotaPolicy {

    /**
     * 活跃需求配额守卫：达到上限拒绝创建。
     *
     * @param owner            属主 userId（异常文案用）
     * @param activeCount      当前非终态需求数（读侧计数）
     * @param maxActivePerUser 上限；非正数表示不设限（配置逃生口）
     */
    public void assertWithinActiveQuota(String owner, int activeCount, int maxActivePerUser) {
        if (maxActivePerUser > 0 && activeCount >= maxActivePerUser) {
            throw new RequirementQuotaExceededException(owner, activeCount, maxActivePerUser);
        }
    }

    /**
     * 每需求并发 run 配额守卫（M2 计划门起启用，发 run 编排的公共前置）。
     *
     * @param requirementId         需求 ID（异常文案用）
     * @param activeRuns            该需求当前在跑 run 数（app 侧 run tracker 计数）
     * @param maxRunsPerRequirement 上限；非正数表示不设限（配置逃生口）
     */
    public void assertWithinRunQuota(String requirementId, int activeRuns, int maxRunsPerRequirement) {
        if (maxRunsPerRequirement > 0 && activeRuns >= maxRunsPerRequirement) {
            throw new RequirementQuotaExceededException(
                    "requirement:" + requirementId, activeRuns, maxRunsPerRequirement);
        }
    }
}
