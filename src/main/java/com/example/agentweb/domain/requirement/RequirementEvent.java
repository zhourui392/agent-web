package com.example.agentweb.domain.requirement;

import lombok.Value;

import java.time.Instant;

/**
 * Requirement 聚合的领域事件（状态迁移审计流水，requirement_event 表的唯一进料口）。
 * 聚合在迁移时收集，Repository 写库时经 {@link Requirement#pullEvents()} 取走落库。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class RequirementEvent {

    public static final String TYPE_CREATED = "CREATED";
    public static final String TYPE_PLAN_ATTACHED = "PLAN_ATTACHED";
    public static final String TYPE_PLAN_REPLACED = "PLAN_REPLACED";
    public static final String TYPE_PLAN_REJECTED = "PLAN_REJECTED";
    public static final String TYPE_APPROVED = "APPROVED";
    public static final String TYPE_WORKSPACE_ATTACHED = "WORKSPACE_ATTACHED";
    public static final String TYPE_IMPLEMENT_STARTED = "IMPLEMENT_STARTED";
    public static final String TYPE_VERIFY_STARTED = "VERIFY_STARTED";
    public static final String TYPE_VERIFICATION_APPLIED = "VERIFICATION_APPLIED";
    public static final String TYPE_DELIVERED = "DELIVERED";
    public static final String TYPE_CHANGES_REQUESTED = "CHANGES_REQUESTED";
    public static final String TYPE_SUSPENDED = "SUSPENDED";
    public static final String TYPE_RESUMED = "RESUMED";
    public static final String TYPE_ARCHIVED = "ARCHIVED";
    public static final String TYPE_MR_DRAFTED = "MR_DRAFTED";
    public static final String TYPE_FIX_SUGGESTED = "FIX_SUGGESTED";
    public static final String TYPE_FIX_RUN_STARTED = "FIX_RUN_STARTED";

    String eventType;
    String actor;
    RequirementStatus fromStatus;
    RequirementStatus toStatus;

    /** 操作附带的业务明细（驳回/挂起原因、验证终态、MR 引用等），审计留痕；无明细为 null。 */
    String detail;

    Instant occurredAt;

    static RequirementEvent of(String eventType, String actor, RequirementStatus from,
                               RequirementStatus to, String detail, Instant at) {
        return new RequirementEvent(eventType, actor, from, to, detail, at);
    }
}
