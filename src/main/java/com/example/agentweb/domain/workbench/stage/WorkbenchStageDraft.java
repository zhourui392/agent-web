package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;

/**
 * Stage Definition 当前可变工作副本。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageDraft {

    private final Long basedOnPublishedRevisionNumber;
    private final WorkbenchStageDraftContent content;
    private final String draftHash;
    private final StageCatalogEditor savedBy;
    private final Instant savedAt;

    private WorkbenchStageDraft(
            Long basedOnPublishedRevisionNumber,
            WorkbenchStageDraftContent content,
            StageCatalogEditor savedBy, Instant savedAt) {
        if (basedOnPublishedRevisionNumber != null
                && basedOnPublishedRevisionNumber.longValue() < 1L) {
            throw new IllegalArgumentException(
                    "based-on Published Revision Number must be positive");
        }
        if (content == null || savedBy == null) {
            throw new IllegalArgumentException("Stage Draft facts are required");
        }
        this.basedOnPublishedRevisionNumber = basedOnPublishedRevisionNumber;
        this.content = content;
        this.draftHash = content.getDraftHash();
        this.savedBy = savedBy;
        this.savedAt = DomainText.requireTime(savedAt, "Stage Draft save time");
    }

    static WorkbenchStageDraft create(
            Long basedOnPublishedRevisionNumber,
            WorkbenchStageDraftContent content,
            StageCatalogEditor savedBy, Instant savedAt) {
        return new WorkbenchStageDraft(
                basedOnPublishedRevisionNumber, content, savedBy, savedAt);
    }

    public static WorkbenchStageDraft restore(
            Long basedOnPublishedRevisionNumber,
            WorkbenchStageDraftContent content, String expectedDraftHash,
            StageCatalogEditor savedBy, Instant savedAt) {
        WorkbenchStageDraft restored = new WorkbenchStageDraft(
                basedOnPublishedRevisionNumber, content, savedBy, savedAt);
        if (!restored.draftHash.equals(expectedDraftHash)) {
            throw new IllegalStateException(
                    "persisted Stage Draft Hash does not match its content");
        }
        return restored;
    }
}
