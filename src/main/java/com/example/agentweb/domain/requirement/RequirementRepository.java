package com.example.agentweb.domain.requirement;

/**
 * 需求写侧仓储（仅聚合 lifecycle；读模型投影走 app 层 RequirementQueryService）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface RequirementRepository {

    void save(Requirement requirement);

    void update(Requirement requirement);

    /**
     * 按 ID 加载聚合。
     *
     * @param requirementId 需求 ID
     * @return 聚合，未命中 null
     */
    Requirement findById(String requirementId);
}
