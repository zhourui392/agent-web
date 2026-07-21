package com.example.agentweb.domain.requirement;

import lombok.Value;

import java.time.Instant;

/**
 * Agent 产出的实施计划（VO，不拆独立聚合——plan 非空正是 approve 的核心守卫，
 * 拆出去会把不变量校验变成跨聚合最终一致，见 detailed-design §1.1）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class AgentPlan {

    String planText;

    /** 生成计划时使用的 prompt 指纹，质量回退时定界；手动贴入为 null。 */
    String promptHash;

    /** 产出计划的 run 引用，仅审计溯源（人审门靠入口收口，不做 ID 比对）；手动贴入为 null。 */
    String sourceRunId;

    Instant attachedAt;
}
