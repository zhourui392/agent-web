package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 绑定当前 Review 会话与人工 Opinion 基线的非持久化 Agent Candidate。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ReviewCandidate {

    public static final int MAX_ITEMS = 50;

    private final OwnerReference owner;
    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final String conversationId;
    private final int conversationGeneration;
    private final long baseOpinionVersion;
    private final int sourceMessageCount;
    private final ReviewCandidateStrategy strategy;
    private final List<ReviewCandidateItem> items;

    ReviewCandidate(
            OwnerReference owner, WorkbenchId workbenchId,
            String conversationId, int conversationGeneration,
            long baseOpinionVersion, int sourceMessageCount,
            ReviewCandidateStrategy strategy,
            List<ReviewCandidateItem> items) {
        if (owner == null || workbenchId == null || strategy == null) {
            throw new IllegalArgumentException(
                    "review candidate binding is required");
        }
        if (conversationGeneration < 0 || baseOpinionVersion < 0L
                || sourceMessageCount < 0
                || sourceMessageCount > ReviewCandidateConversation.MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "review candidate binding counters are invalid");
        }
        this.owner = owner;
        this.workbenchId = workbenchId;
        this.phase = WorkbenchPhase.REVIEW_REFACTOR;
        this.conversationId = DomainText.require(
                conversationId, "review candidate conversation id", 128);
        this.conversationGeneration = conversationGeneration;
        this.baseOpinionVersion = baseOpinionVersion;
        this.sourceMessageCount = sourceMessageCount;
        this.strategy = strategy;
        this.items = items(items);
    }

    private List<ReviewCandidateItem> items(
            List<ReviewCandidateItem> values) {
        if (values == null || values.contains(null)
                || values.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(
                    "review candidate items are invalid");
        }
        List<ReviewCandidateItem> result =
                new ArrayList<ReviewCandidateItem>(values);
        Set<String> identities = new HashSet<String>();
        for (ReviewCandidateItem item : result) {
            if (!identities.add(item.getItemId())) {
                throw new IllegalArgumentException(
                        "review candidate item ids must be unique");
            }
        }
        return Collections.unmodifiableList(result);
    }
}
