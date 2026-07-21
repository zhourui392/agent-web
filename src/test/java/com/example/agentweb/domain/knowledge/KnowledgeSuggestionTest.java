package com.example.agentweb.domain.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识建议收件箱聚合（M4）：候选只进不自动落盘，人工审批门是唯一出口——
 * PENDING → APPROVED（落盘后记 issueId）/ REJECTED，非 PENDING 不可再审、不可再编辑。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class KnowledgeSuggestionTest {

    private static final String REVIEWER = "V33215020";

    @Test
    public void create_should_initialize_pending_and_require_title_and_requirement() {
        KnowledgeSuggestion suggestion = pendingSuggestion();

        assertNotNull(suggestion.getId());
        assertEquals(SuggestionStatus.PENDING, suggestion.getStatus());
        assertEquals("R2607040001", suggestion.getRequirementId());
        assertEquals(KnowledgeScope.REPO, suggestion.getScope());
        assertNotNull(suggestion.getCreatedAt());

        assertThrows(IllegalArgumentException.class, () -> KnowledgeSuggestion.create(
                " ", KnowledgeScope.REPO, "mr!1", "标题", "现象", "根因", "方案"));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeSuggestion.create(
                "R1", KnowledgeScope.REPO, "mr!1", " ", "现象", "根因", "方案"));
    }

    @Test
    public void approve_should_transition_pending_to_approved_with_reviewer() {
        KnowledgeSuggestion suggestion = pendingSuggestion();

        suggestion.approve(REVIEWER);

        assertEquals(SuggestionStatus.APPROVED, suggestion.getStatus());
        assertEquals(REVIEWER, suggestion.getReviewedBy());
        assertNotNull(suggestion.getReviewedAt());
        assertThrows(IllegalArgumentException.class, () -> pendingSuggestion().approve(" "));
    }

    @Test
    public void approve_should_reject_when_not_pending() {
        KnowledgeSuggestion approved = pendingSuggestion();
        approved.approve(REVIEWER);
        KnowledgeSuggestion rejected = pendingSuggestion();
        rejected.reject(REVIEWER, "重复知识");

        assertThrows(IllegalStateException.class, () -> approved.approve(REVIEWER));
        assertThrows(IllegalStateException.class, () -> rejected.approve(REVIEWER));
    }

    @Test
    public void reject_should_require_pending_and_reason() {
        KnowledgeSuggestion suggestion = pendingSuggestion();

        suggestion.reject(REVIEWER, "已有同类条目");

        assertEquals(SuggestionStatus.REJECTED, suggestion.getStatus());
        assertEquals("已有同类条目", suggestion.getRejectReason());
        assertThrows(IllegalArgumentException.class,
                () -> pendingSuggestion().reject(REVIEWER, " "));
        assertThrows(IllegalStateException.class,
                () -> suggestion.reject(REVIEWER, "再拒一次"));
    }

    @Test
    public void recordArchived_should_only_work_after_approve() {
        KnowledgeSuggestion suggestion = pendingSuggestion();
        assertThrows(IllegalStateException.class,
                () -> suggestion.recordArchived("I-001", "docs/issue-log/issue/I-001.md"));

        suggestion.approve(REVIEWER);
        suggestion.recordArchived("I-001", "docs/issue-log/issue/I-001.md");

        assertEquals("I-001", suggestion.getIssueId());
        assertEquals("docs/issue-log/issue/I-001.md", suggestion.getIssuePath());
    }

    @Test
    public void reviseDraft_should_only_work_when_pending() {
        KnowledgeSuggestion suggestion = pendingSuggestion();

        suggestion.reviseDraft("新标题", List.of("ERR_TIMEOUT", "下单超时"),
                "新现象", "新根因", "新方案", "备注");

        assertEquals("新标题", suggestion.getTitle());
        assertEquals(List.of("ERR_TIMEOUT", "下单超时"), suggestion.getTriggerSignals());
        assertEquals("备注", suggestion.getNotes());

        suggestion.approve(REVIEWER);
        assertThrows(IllegalStateException.class, () -> suggestion.reviseDraft(
                "再改", List.of(), "x", "x", "x", ""));
    }

    @Test
    public void reviseDraft_should_keep_title_required_and_filter_blank_signals() {
        KnowledgeSuggestion suggestion = pendingSuggestion();

        assertThrows(IllegalArgumentException.class, () -> suggestion.reviseDraft(
                " ", List.of(), "x", "x", "x", ""));

        suggestion.reviseDraft("标题", List.of(" ", "有效词", ""), "x", "x", "x", "");
        assertEquals(List.of("有效词"), suggestion.getTriggerSignals());
        assertTrue(suggestion.getNotes().isEmpty());
    }

    private KnowledgeSuggestion pendingSuggestion() {
        return KnowledgeSuggestion.create("R2607040001", KnowledgeScope.REPO,
                "MR !12", "需求交付知识: 下单超时修复", "下单接口偶发超时", "连接池耗尽", "调大池并加熔断");
    }
}
