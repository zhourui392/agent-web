package com.example.agentweb.domain.workspace;

import java.time.Instant;
import java.util.List;

/**
 * 工作区聚合生命周期仓储（写侧，签名只用 domain 类型；端口租约是 infra 关注点不在此接口）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface WorkspaceRepository {

    /** 插入或整体覆盖（工作区无事件流水，save 即 upsert）。 */
    void save(RequirementWorkspace workspace);

    RequirementWorkspace findById(String workspaceId);

    RequirementWorkspace findByRequirementId(String requirementId);

    /** TTL 清理候选：lastActiveAt 早于给定时刻且未 RELEASED。 */
    List<RequirementWorkspace> findIdleBefore(Instant instant);
}
