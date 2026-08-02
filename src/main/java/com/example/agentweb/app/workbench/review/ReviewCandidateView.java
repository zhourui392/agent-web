package com.example.agentweb.app.workbench.review;

import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.ReviewCandidate;
import com.example.agentweb.domain.workbench.ReviewCandidateItem;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 非持久化 Review Candidate 的 Owner-safe 应用投影。
 *
 * <p>不暴露 owner、workbenchId、conversationId 或任何物理路径。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ReviewCandidateView {

    private final WorkbenchPhase phase;
    private final long baseOpinionVersion;
    private final int conversationGeneration;
    private final int sourceMessageCount;
    private final String strategy;
    private final List<ItemView> items;

    private ReviewCandidateView(ReviewCandidate candidate) {
        this.phase = candidate.getPhase();
        this.baseOpinionVersion = candidate.getBaseOpinionVersion();
        this.conversationGeneration = candidate.getConversationGeneration();
        this.sourceMessageCount = candidate.getSourceMessageCount();
        this.strategy = candidate.getStrategy().name();
        this.items = items(candidate.getItems());
    }

    public static ReviewCandidateView from(ReviewCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "review candidate is required");
        }
        return new ReviewCandidateView(candidate);
    }

    private static List<ItemView> items(
            List<ReviewCandidateItem> values) {
        List<ItemView> result = new ArrayList<ItemView>(values.size());
        for (ReviewCandidateItem value : values) {
            result.add(new ItemView(value));
        }
        return Collections.unmodifiableList(result);
    }

    /** Candidate 单条意见的安全公开字段。 */
    @Getter
    @EqualsAndHashCode
    public static final class ItemView {
        private final String itemId;
        private final String finding;
        private final String impact;
        private final String suggestedChange;
        private final List<DocumentReferenceView> affectedFiles;
        private final List<String> suggestedTests;

        private ItemView(ReviewCandidateItem item) {
            this.itemId = item.getItemId();
            this.finding = item.getFinding();
            this.impact = item.getImpact();
            this.suggestedChange = item.getSuggestedChange();
            this.affectedFiles = files(item.getAffectedFiles());
            this.suggestedTests = Collections.unmodifiableList(
                    new ArrayList<String>(item.getSuggestedTests()));
        }

        private static List<DocumentReferenceView> files(
                List<DocumentReference> values) {
            List<DocumentReferenceView> result =
                    new ArrayList<DocumentReferenceView>(values.size());
            for (DocumentReference value : values) {
                result.add(new DocumentReferenceView(
                        value.getRepositoryKey(), value.getRelativePath()));
            }
            return Collections.unmodifiableList(result);
        }
    }

    /** Repository Scope 内的逻辑文件引用。 */
    @Getter
    @EqualsAndHashCode
    public static final class DocumentReferenceView {
        private final String repositoryKey;
        private final String relativePath;

        private DocumentReferenceView(
                String repositoryKey, String relativePath) {
            this.repositoryKey = repositoryKey;
            this.relativePath = relativePath;
        }
    }
}
