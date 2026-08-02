package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.review.ReviewCandidateView;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 浏览器人工选取和编辑使用的非持久化 Review Candidate 响应。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ReviewCandidateResponse {

    private final WorkbenchPhase phase;
    private final long baseOpinionVersion;
    private final int conversationGeneration;
    private final int sourceMessageCount;
    private final String strategy;
    private final List<ItemResponse> items;

    private ReviewCandidateResponse(ReviewCandidateView view) {
        this.phase = view.getPhase();
        this.baseOpinionVersion = view.getBaseOpinionVersion();
        this.conversationGeneration = view.getConversationGeneration();
        this.sourceMessageCount = view.getSourceMessageCount();
        this.strategy = view.getStrategy();
        this.items = items(view.getItems());
    }

    public static ReviewCandidateResponse from(ReviewCandidateView view) {
        if (view == null) {
            throw new IllegalArgumentException(
                    "review candidate view is required");
        }
        return new ReviewCandidateResponse(view);
    }

    private static List<ItemResponse> items(
            List<ReviewCandidateView.ItemView> values) {
        List<ItemResponse> result =
                new ArrayList<ItemResponse>(values.size());
        for (ReviewCandidateView.ItemView value : values) {
            result.add(new ItemResponse(value));
        }
        return Collections.unmodifiableList(result);
    }

    /** 单条候选意见；没有 accepted 或 confirmation 字段。 */
    @Getter
    public static final class ItemResponse {
        private final String itemId;
        private final String finding;
        private final String impact;
        private final String suggestedChange;
        private final List<DocumentReferenceResponse> affectedFiles;
        private final List<String> suggestedTests;

        private ItemResponse(ReviewCandidateView.ItemView item) {
            this.itemId = item.getItemId();
            this.finding = item.getFinding();
            this.impact = item.getImpact();
            this.suggestedChange = item.getSuggestedChange();
            this.affectedFiles = files(item.getAffectedFiles());
            this.suggestedTests = Collections.unmodifiableList(
                    new ArrayList<String>(item.getSuggestedTests()));
        }

        private static List<DocumentReferenceResponse> files(
                List<ReviewCandidateView.DocumentReferenceView> values) {
            List<DocumentReferenceResponse> result =
                    new ArrayList<DocumentReferenceResponse>(values.size());
            for (ReviewCandidateView.DocumentReferenceView value : values) {
                result.add(new DocumentReferenceResponse(
                        value.getRepositoryKey(), value.getRelativePath()));
            }
            return Collections.unmodifiableList(result);
        }
    }

    /** Repository Scope 内的逻辑文件引用。 */
    @Getter
    public static final class DocumentReferenceResponse {
        private final String repositoryKey;
        private final String relativePath;

        private DocumentReferenceResponse(
                String repositoryKey, String relativePath) {
            this.repositoryKey = repositoryKey;
            this.relativePath = relativePath;
        }
    }
}
