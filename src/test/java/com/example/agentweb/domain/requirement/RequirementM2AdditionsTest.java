package com.example.agentweb.domain.requirement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2 聚合增量：带 sourceRef 的工厂、计划门前置断言、MR 草稿/修复建议审计事件、issue owner 回落策略。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RequirementM2AdditionsTest {

    private static final String OWNER = "V33215020";

    // ---- create with sourceRef ----

    @Test
    public void create_with_sourceRef_should_keep_ref_and_record_created() {
        Requirement requirement = Requirement.create(RequirementSource.GITLAB_ISSUE,
                "https://gitlab/x/issues/1", "标题", "描述", OWNER);

        assertEquals("https://gitlab/x/issues/1", requirement.getSourceRef());
        assertEquals(RequirementSource.GITLAB_ISSUE, requirement.getSource());
        List<RequirementEvent> events = requirement.pullEvents();
        assertEquals(1, events.size());
        assertEquals(RequirementEvent.TYPE_CREATED, events.get(0).getEventType());
    }

    // ---- assertPlanAttachable：与 attachPlan 同源守卫 ----

    @Test
    public void assertPlanAttachable_should_pass_on_intake_and_planned() {
        Requirement intake = Requirement.create(RequirementSource.BOARD, "t", "d", OWNER);
        assertDoesNotThrow(intake::assertPlanAttachable);

        intake.attachPlan(new AgentPlan("p", null, null, java.time.Instant.now()), OWNER);
        assertDoesNotThrow(intake::assertPlanAttachable);
    }

    @Test
    public void assertPlanAttachable_should_reject_after_approve() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", OWNER);
        requirement.attachPlan(new AgentPlan("p", null, null, java.time.Instant.now()), OWNER);
        requirement.approve(OWNER);

        assertThrows(IllegalRequirementTransitionException.class, requirement::assertPlanAttachable);
    }

    // ---- recordMrDrafted / recordFixSuggestion：审计事件不改状态 ----

    @Test
    public void recordMrDrafted_should_append_event_without_status_change() {
        Requirement requirement = implementing();
        requirement.pullEvents();

        requirement.recordMrDrafted("https://gitlab/mr/1", OWNER);

        assertEquals(RequirementStatus.IMPLEMENTING, requirement.getStatus());
        List<RequirementEvent> events = requirement.pullEvents();
        assertEquals(1, events.size());
        assertEquals(RequirementEvent.TYPE_MR_DRAFTED, events.get(0).getEventType());
        assertEquals("https://gitlab/mr/1", events.get(0).getDetail());
    }

    @Test
    public void recordFixSuggestion_should_append_event_without_status_change() {
        Requirement requirement = implementing();
        requirement.pullEvents();

        requirement.recordFixSuggestion("system:webhook", "pipeline failed: https://ci/1");

        assertEquals(RequirementStatus.IMPLEMENTING, requirement.getStatus());
        List<RequirementEvent> events = requirement.pullEvents();
        assertEquals(1, events.size());
        assertEquals(RequirementEvent.TYPE_FIX_SUGGESTED, events.get(0).getEventType());
    }

    @Test
    public void audit_records_should_reject_terminal_status() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", OWNER);
        requirement.archive(OWNER, "done");

        assertThrows(IllegalStateException.class,
                () -> requirement.recordMrDrafted("url", OWNER));
        assertThrows(IllegalStateException.class,
                () -> requirement.recordFixSuggestion("system:webhook", "x"));
    }

    // ---- IntakeOwnerPolicy：owner 映射回落链 ----

    @Test
    public void ownerPolicy_should_prefer_author_username() {
        assertEquals("V123", new IntakeOwnerPolicy().resolveOwner(" V123 ", "V999"));
    }

    @Test
    public void ownerPolicy_should_fallback_to_default_owner() {
        assertEquals("V999", new IntakeOwnerPolicy().resolveOwner(" ", "V999"));
        assertEquals("V999", new IntakeOwnerPolicy().resolveOwner(null, "V999"));
    }

    @Test
    public void ownerPolicy_should_reject_when_no_candidate() {
        OwnerUnresolvedException ex = assertThrows(OwnerUnresolvedException.class,
                () -> new IntakeOwnerPolicy().resolveOwner(null, " "));
        assertTrue(ex.getMessage().contains("owner"));
    }

    @Test
    public void ownerPolicy_with_directory_should_skip_unknown_author_and_use_fallback() {
        // 作者不在目录(如外部承包商 GitLab 账号) → 回落接待人,不建无主需求
        assertEquals("V999", new IntakeOwnerPolicy()
                .resolveOwner("outsider", "V999", "V999"::equals));
    }

    @Test
    public void ownerPolicy_with_directory_should_reject_when_both_unknown() {
        assertThrows(OwnerUnresolvedException.class,
                () -> new IntakeOwnerPolicy().resolveOwner("outsider", "V999", user -> false));
    }

    @Test
    public void ownerPolicy_with_directory_should_accept_known_author() {
        assertEquals("V123", new IntakeOwnerPolicy()
                .resolveOwner(" V123 ", "V999", "V123"::equals));
    }

    // ---- run 配额：每需求并发 run 数 ----

    @Test
    public void runQuota_should_reject_when_active_runs_reach_max() {
        RequirementQuotaPolicy policy = new RequirementQuotaPolicy();

        assertDoesNotThrow(() -> policy.assertWithinRunQuota("R1", 1, 2));
        assertThrows(RequirementQuotaExceededException.class,
                () -> policy.assertWithinRunQuota("R1", 2, 2));
    }

    @Test
    public void runQuota_should_be_unlimited_when_max_not_positive() {
        RequirementQuotaPolicy policy = new RequirementQuotaPolicy();

        assertDoesNotThrow(() -> policy.assertWithinRunQuota("R1", 99, 0));
        assertDoesNotThrow(() -> policy.assertWithinRunQuota("R1", 99, -1));
    }

    private Requirement implementing() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", OWNER);
        requirement.attachPlan(new AgentPlan("p", null, null, java.time.Instant.now()), OWNER);
        requirement.approve(OWNER);
        requirement.attachWorkspace("W1");
        requirement.startImplement(OWNER);
        return requirement;
    }
}
