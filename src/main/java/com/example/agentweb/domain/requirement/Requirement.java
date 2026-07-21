package com.example.agentweb.domain.requirement;

import com.example.agentweb.domain.verification.VerificationOutcome;
import lombok.AccessLevel;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 需求聚合根：状态机 + 审批门 + 事件审计（detailed-design §1.2/§1.3）。
 * 迁移合法性统一先查 {@link RequirementTransitions}，授权委托 {@link RequirementAccessPolicy}。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Getter
public class Requirement {

    private static final RequirementAccessPolicy ACCESS_POLICY = new RequirementAccessPolicy();

    private final RequirementId id;
    private final RequirementSource source;

    /** 来源引用（GitLab issue URL / 派生 ticketId），看板/REST 直建为 null。 */
    private final String sourceRef;

    private String title;
    private String description;
    private RequirementStatus status;

    /** T12/T9 挂起前状态，T13 resume 的回归目标；非挂起态恒为 null。 */
    private RequirementStatus statusBeforeSuspend;

    private AgentPlan plan;

    /** 属主登录用户 ID（T4 审批权判据）。 */
    private final String owner;

    private List<String> participants;

    /** 跨聚合 ID 引用（M1 起使用），T6 startImplement 的前置。 */
    private String workspaceId;

    private final Instant createdAt;
    private Instant updatedAt;

    /** 待持久化的领域事件，Repository 写库时 {@link #pullEvents()} 取走；从 DB 重建的聚合初始为空。 */
    @Getter(AccessLevel.NONE)
    private final List<RequirementEvent> pendingEvents = new ArrayList<>();

    public static Requirement create(RequirementSource source, String title, String description, String owner) {
        return create(source, null, title, description, owner);
    }

    /** 带来源引用的工厂（M2 GitLab issue / 外部 REST 接入：sourceRef 记 issue URL 等回链）。 */
    public static Requirement create(RequirementSource source, String sourceRef, String title,
                                     String description, String owner) {
        Requirement requirement = new Requirement(RequirementId.newId(), source, sourceRef, title, description,
                RequirementStatus.INTAKE, null, null, owner, new ArrayList<>(), null,
                Instant.now(), Instant.now());
        requirement.record(RequirementEvent.TYPE_CREATED, requirement.owner, null,
                RequirementStatus.INTAKE, null);
        return requirement;
    }

    /** 全量重建构造器供 Repository 使用（对齐 Ticket 模式），构造期不变量在此收口。 */
    public Requirement(RequirementId id, RequirementSource source, String sourceRef, String title,
                       String description, RequirementStatus status, RequirementStatus statusBeforeSuspend,
                       AgentPlan plan, String owner, List<String> participants, String workspaceId,
                       Instant createdAt, Instant updatedAt) {
        if (id == null) {
            throw new IllegalArgumentException("requirement id required");
        }
        if (source == null) {
            throw new IllegalArgumentException("source required");
        }
        if (isBlank(title)) {
            throw new IllegalArgumentException("title required");
        }
        if (isBlank(owner)) {
            throw new IllegalArgumentException("owner required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status required");
        }
        this.id = id;
        this.source = source;
        this.sourceRef = sourceRef;
        this.title = title.trim();
        this.description = description;
        this.status = status;
        this.statusBeforeSuspend = statusBeforeSuspend;
        this.plan = plan;
        this.owner = owner.trim();
        this.participants = participants == null ? new ArrayList<>() : new ArrayList<>(participants);
        this.workspaceId = workspaceId;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    public List<String> getParticipants() {
        return Collections.unmodifiableList(participants);
    }

    /** T1/T2：贴计划（INTAKE 首贴 / PLANNED 重计划覆盖，历史由 PLAN_REPLACED 事件承载）。 */
    public void attachPlan(AgentPlan newPlan, String actor) {
        RequirementTransitions.assertAllowed(status, RequirementAction.ATTACH_PLAN);
        requireActor(actor);
        if (newPlan == null || isBlank(newPlan.getPlanText())) {
            throw new IllegalArgumentException("plan text required");
        }
        boolean replacing = this.status == RequirementStatus.PLANNED;
        RequirementStatus from = this.status;
        this.plan = newPlan;
        this.status = RequirementStatus.PLANNED;
        touch();
        record(replacing ? RequirementEvent.TYPE_PLAN_REPLACED : RequirementEvent.TYPE_PLAN_ATTACHED,
                actor, from, status, null);
    }

    /** T3：驳回计划回 INTAKE，reason 必填。plan 保留供重计划参考，历史在事件流水。 */
    public void rejectPlan(String actor, String reason) {
        RequirementTransitions.assertAllowed(status, RequirementAction.REJECT_PLAN);
        requireActor(actor);
        requireText(reason, "reason required");
        RequirementStatus from = this.status;
        this.status = RequirementStatus.INTAKE;
        touch();
        record(RequirementEvent.TYPE_PLAN_REJECTED, actor, from, status, reason.trim());
    }

    /** T4：审批通过（人审门）。plan 非空 + owner 判定，入口收口保证审批来自人类请求。 */
    public void approve(String actor) {
        RequirementTransitions.assertAllowed(status, RequirementAction.APPROVE);
        if (plan == null) {
            throw new PlanRequiredException(id.getValue());
        }
        if (!ACCESS_POLICY.canApprove(actor, this)) {
            throw new ApprovalNotAllowedException(actor);
        }
        RequirementStatus from = this.status;
        this.status = RequirementStatus.APPROVED;
        touch();
        record(RequirementEvent.TYPE_APPROVED, actor, from, status, null);
    }

    /** T5 前置断言：供工作区编排在昂贵的 git 供给前快速失败，守卫与 attachWorkspace 同源。 */
    public void assertWorkspaceAttachable() {
        RequirementTransitions.assertAllowed(status, RequirementAction.ATTACH_WORKSPACE);
    }

    /** T1/T2 前置断言：供计划门编排在昂贵的 plan run 前快速失败，守卫与 attachPlan 同源。 */
    public void assertPlanAttachable() {
        RequirementTransitions.assertAllowed(status, RequirementAction.ATTACH_PLAN);
    }

    /** 审计事件：草稿 MR 已创建（不改状态；交付终态 T10 仍由 MrMerged webhook 驱动）。 */
    public void recordMrDrafted(String mrUrl, String actor) {
        requireNotTerminal("recordMrDrafted");
        requireActor(actor);
        requireText(mrUrl, "mrUrl required");
        record(RequirementEvent.TYPE_MR_DRAFTED, actor, status, status, mrUrl.trim());
    }

    /** 审计事件：webhook 触发的 fix-run 建议（先人工确认再执行，不自动触发——master-plan §五约束）。 */
    public void recordFixSuggestion(String actor, String detail) {
        requireNotTerminal("recordFixSuggestion");
        requireActor(actor);
        requireText(detail, "detail required");
        record(RequirementEvent.TYPE_FIX_SUGGESTED, actor, status, status, detail.trim());
    }

    /** T5：挂工作区（仅 APPROVED，状态不变，跨聚合只存 ID）。app 编排调用，actor 记 system。 */
    public void attachWorkspace(String workspaceId) {
        RequirementTransitions.assertAllowed(status, RequirementAction.ATTACH_WORKSPACE);
        requireText(workspaceId, "workspaceId required");
        this.workspaceId = workspaceId.trim();
        touch();
        record(RequirementEvent.TYPE_WORKSPACE_ATTACHED, "system", status, status, this.workspaceId);
    }

    /** T6：开始实现，前置是工作区已挂（M0 无工作区故本迁移不可达，属预期）。 */
    public void startImplement(String actor) {
        RequirementTransitions.assertAllowed(status, RequirementAction.START_IMPLEMENT);
        requireActor(actor);
        if (isBlank(workspaceId)) {
            throw new IllegalStateException("startImplement rejected: workspace not attached");
        }
        RequirementStatus from = this.status;
        this.status = RequirementStatus.IMPLEMENTING;
        touch();
        record(RequirementEvent.TYPE_IMPLEMENT_STARTED, actor, from, status, null);
    }

    /**
     * FIX run 发起守卫 + 审计：仅 IMPLEMENTING 可发（REVIEW 退回 T11 / 熔断 resume T13 后均回此态），
     * 不改状态——修复是实现态内的再执行，重验仍走 T7。
     */
    public void startFixRun(String actor) {
        RequirementTransitions.assertAllowed(status, RequirementAction.START_FIX);
        requireActor(actor);
        record(RequirementEvent.TYPE_FIX_RUN_STARTED, actor, status, status, null);
    }

    /** T7：进入验证（M2.5 起由验证编排触发，端点保留供手动重验）。 */
    public void startVerify(String actor) {
        RequirementTransitions.assertAllowed(status, RequirementAction.START_VERIFY);
        requireActor(actor);
        RequirementStatus from = this.status;
        this.status = RequirementStatus.VERIFYING;
        touch();
        record(RequirementEvent.TYPE_VERIFY_STARTED, actor, from, status, null);
    }

    /**
     * T8/T9：应用验证终态。VERIFIED → REVIEW；BLOCKED/DEPLOY_FAILED → SUSPENDED，
     * statusBeforeSuspend 固定记 IMPLEMENTING——人工接管后 resume 回实现态先修再重验，
     * 不回 VERIFYING（否则 resume 守卫恒失败，熔断需求永远无法恢复）。
     */
    public void applyVerificationOutcome(VerificationOutcome outcome, String actor) {
        RequirementTransitions.assertAllowed(status, RequirementAction.APPLY_VERIFICATION_OUTCOME);
        requireActor(actor);
        if (outcome == null) {
            throw new IllegalArgumentException("outcome required");
        }
        RequirementStatus from = this.status;
        if (outcome == VerificationOutcome.VERIFIED) {
            this.status = RequirementStatus.REVIEW;
        } else {
            this.statusBeforeSuspend = RequirementStatus.IMPLEMENTING;
            this.status = RequirementStatus.SUSPENDED;
        }
        touch();
        record(RequirementEvent.TYPE_VERIFICATION_APPLIED, actor, from, status, outcome.name());
    }

    /** T10：交付终态（M2 起 webhook MrMerged 驱动，actor 记 system:webhook）。 */
    public void markDelivered(String actor, String mrRef) {
        RequirementTransitions.assertAllowed(status, RequirementAction.MARK_DELIVERED);
        requireActor(actor);
        RequirementStatus from = this.status;
        this.status = RequirementStatus.DELIVERED;
        touch();
        record(RequirementEvent.TYPE_DELIVERED, actor, from, status, mrRef);
    }

    /** T11：评审退回实现。 */
    public void requestChanges(String actor, String reason) {
        RequirementTransitions.assertAllowed(status, RequirementAction.REQUEST_CHANGES);
        requireActor(actor);
        requireText(reason, "reason required");
        RequirementStatus from = this.status;
        this.status = RequirementStatus.IMPLEMENTING;
        touch();
        record(RequirementEvent.TYPE_CHANGES_REQUESTED, actor, from, status, reason.trim());
    }

    /** T12：挂起并记回归点。SUSPENDED 态不允许再挂起（防覆盖 statusBeforeSuspend）。 */
    public void suspend(String actor, String reason) {
        RequirementTransitions.assertAllowed(status, RequirementAction.SUSPEND);
        requireActor(actor);
        requireText(reason, "reason required");
        RequirementStatus from = this.status;
        this.statusBeforeSuspend = from;
        this.status = RequirementStatus.SUSPENDED;
        touch();
        record(RequirementEvent.TYPE_SUSPENDED, actor, from, status, reason.trim());
    }

    /** T13：恢复到挂起前状态并清空回归点。 */
    public void resume(String actor) {
        RequirementTransitions.assertAllowed(status, RequirementAction.RESUME);
        requireActor(actor);
        if (statusBeforeSuspend == null) {
            throw new IllegalStateException("resume rejected: no status before suspend");
        }
        RequirementStatus from = this.status;
        this.status = statusBeforeSuspend;
        this.statusBeforeSuspend = null;
        touch();
        record(RequirementEvent.TYPE_RESUMED, actor, from, status, null);
    }

    /** T14：归档终态（任意非终态可达），reason 可空。 */
    public void archive(String actor, String reason) {
        RequirementTransitions.assertAllowed(status, RequirementAction.ARCHIVE);
        requireActor(actor);
        RequirementStatus from = this.status;
        this.status = RequirementStatus.ARCHIVED;
        touch();
        record(RequirementEvent.TYPE_ARCHIVED, actor, from, status, reason);
    }

    /** 追加参与者（可看可追加，不可批），幂等。 */
    public void addParticipant(String userId) {
        requireText(userId, "participant userId required");
        String trimmed = userId.trim();
        if (!participants.contains(trimmed) && !trimmed.equals(owner)) {
            participants.add(trimmed);
            touch();
        }
    }

    /**
     * 取走并清空待持久化的领域事件，Repository 写库时调用。
     *
     * @return 自上次 pull 以来累积的事件（按发生顺序）
     */
    public List<RequirementEvent> pullEvents() {
        List<RequirementEvent> events = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    private void record(String eventType, String actor, RequirementStatus from,
                        RequirementStatus to, String detail) {
        pendingEvents.add(RequirementEvent.of(eventType, actor, from, to, detail, updatedAt));
    }

    private void requireActor(String actor) {
        requireText(actor, "actor required");
    }

    private void requireNotTerminal(String operation) {
        if (status.isTerminal()) {
            throw new IllegalStateException(operation + " rejected: requirement is terminal " + status);
        }
    }

    private void requireText(String value, String message) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
