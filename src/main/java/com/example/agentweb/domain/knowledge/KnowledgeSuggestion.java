package com.example.agentweb.domain.knowledge;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 知识建议聚合根（M4 收件箱）：run/交付产出的知识候选，人工审批门是入库唯一出口——
 * PENDING 可编辑可审批；APPROVED 后经 issue-log 通道落盘并回填 issueId；REJECTED 留因。
 * 聚合 persistence-ignorant，落盘编排在 app 层。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Getter
public class KnowledgeSuggestion {

    private final String id;
    private final String requirementId;
    private final KnowledgeScope scope;
    private final String sourceRef;
    private String title;
    private List<String> triggerSignals;
    private String phenomenon;
    private String rootCause;
    private String solution;
    private String notes;
    private SuggestionStatus status;
    private String rejectReason;
    private String reviewedBy;
    private Instant reviewedAt;
    private String issueId;
    private String issuePath;
    private final Instant createdAt;

    /** 再水化构造器（对齐 Requirement 先例）：审批态字段原样恢复，业务不变量在行为方法上。 */
    public KnowledgeSuggestion(String id, String requirementId, KnowledgeScope scope, String sourceRef,
                               String title, List<String> triggerSignals, String phenomenon,
                               String rootCause, String solution, String notes,
                               SuggestionStatus status, String rejectReason, String reviewedBy,
                               Instant reviewedAt, String issueId, String issuePath, Instant createdAt) {
        this.id = requireText(id, "id required");
        this.requirementId = requireText(requirementId, "requirementId required");
        this.scope = scope == null ? KnowledgeScope.REPO : scope;
        this.sourceRef = sourceRef == null ? "" : sourceRef.trim();
        this.title = requireText(title, "title required");
        this.triggerSignals = sanitize(triggerSignals);
        this.phenomenon = nullToEmpty(phenomenon);
        this.rootCause = nullToEmpty(rootCause);
        this.solution = nullToEmpty(solution);
        this.notes = nullToEmpty(notes);
        this.status = status == null ? SuggestionStatus.PENDING : status;
        this.rejectReason = rejectReason;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.issueId = issueId;
        this.issuePath = issuePath;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /** 工厂：新候选恒 PENDING（收件箱只进不自动出）。 */
    public static KnowledgeSuggestion create(String requirementId, KnowledgeScope scope, String sourceRef,
                                             String title, String phenomenon, String rootCause,
                                             String solution) {
        return new KnowledgeSuggestion("KS-" + UUID.randomUUID(), requirementId, scope, sourceRef,
                title, List.of(), phenomenon, rootCause, solution, "",
                SuggestionStatus.PENDING, null, null, null, null, null, Instant.now());
    }

    /** 审批通过；仅 PENDING 允许。落盘由 app 编排负责，成功后调 {@link #recordArchived}。 */
    public void approve(String reviewer) {
        assertPending("approve");
        this.reviewedBy = requireText(reviewer, "reviewer required");
        this.reviewedAt = Instant.now();
        this.status = SuggestionStatus.APPROVED;
    }

    /** 拒绝；仅 PENDING 允许，理由必填（反例池后续挖掘依据）。 */
    public void reject(String reviewer, String reason) {
        assertPending("reject");
        this.reviewedBy = requireText(reviewer, "reviewer required");
        this.rejectReason = requireText(reason, "reject reason required");
        this.reviewedAt = Instant.now();
        this.status = SuggestionStatus.REJECTED;
    }

    /** 落盘回执：仅 APPROVED 允许记录 issue-log 归档结果。 */
    public void recordArchived(String archivedIssueId, String archivedPath) {
        if (status != SuggestionStatus.APPROVED) {
            throw new IllegalStateException("recordArchived requires APPROVED, current=" + status);
        }
        this.issueId = requireText(archivedIssueId, "issueId required");
        this.issuePath = requireText(archivedPath, "issuePath required");
    }

    /** 审批前编辑草稿（补触发词等）；仅 PENDING 允许。 */
    public void reviseDraft(String newTitle, List<String> newTriggerSignals, String newPhenomenon,
                            String newRootCause, String newSolution, String newNotes) {
        assertPending("reviseDraft");
        this.title = requireText(newTitle, "title required");
        this.triggerSignals = sanitize(newTriggerSignals);
        this.phenomenon = nullToEmpty(newPhenomenon);
        this.rootCause = nullToEmpty(newRootCause);
        this.solution = nullToEmpty(newSolution);
        this.notes = nullToEmpty(newNotes);
    }

    private void assertPending(String action) {
        if (status != SuggestionStatus.PENDING) {
            throw new IllegalStateException(action + " requires PENDING, current=" + status);
        }
    }

    private static List<String> sanitize(List<String> tokens) {
        if (tokens == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String token : tokens) {
            if (token != null && !token.isBlank()) {
                cleaned.add(token.trim());
            }
        }
        return Collections.unmodifiableList(cleaned);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
