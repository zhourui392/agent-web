package com.example.agentweb.app.workbench.review;

import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

/**
 * 不暴露 actor、但包含有界人工正文的 Review Opinion Owner-safe 投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ReviewOpinionView {

    private final WorkbenchPhase phase;
    private final long version;
    private final String content;
    private final String contentHash;
    private final long reviewedAt;
    private final boolean readOnly;

    private ReviewOpinionView(
            WorkbenchPhase phase, long version, String content,
            String contentHash,
            long reviewedAt, boolean readOnly) {
        this.phase = phase;
        this.version = version;
        this.content = content;
        this.contentHash = contentHash;
        this.reviewedAt = reviewedAt;
        this.readOnly = readOnly;
    }

    public static ReviewOpinionView from(
            ReviewOpinion opinion, boolean readOnly) {
        return new ReviewOpinionView(
                opinion.getPhase(), opinion.getVersion(), opinion.getContent(),
                opinion.getContentHash(),
                opinion.getReviewedAt().toEpochMilli(), readOnly);
    }
}
