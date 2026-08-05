package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 动态 Stage 的稳定身份、当前 Draft 与 Published Revision 指针。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageDefinition {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9_-]{0,127}");

    private final String definitionIdentifier;
    private WorkbenchStageDraft draft;
    private WorkbenchStageDefinitionRevision currentPublishedRevision;
    private final List<WorkbenchStageDefinitionRevision> revisionHistory;
    private boolean disabled;
    private final StageCatalogEditor createdBy;
    private final Instant createdAt;
    private StageCatalogEditor updatedBy;
    private Instant updatedAt;
    private long version;

    private WorkbenchStageDefinition(
            String definitionIdentifier, WorkbenchStageDraftContent content,
            StageCatalogEditor editor, Instant createdAt) {
        String identifier = DomainText.require(
                definitionIdentifier, "Stage Definition identifier", 128);
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new StageCatalogException("WORKBENCH_STAGE_DEFINITION_INVALID",
                    "Stage Definition identifier must use a stable lowercase name");
        }
        this.definitionIdentifier = identifier;
        this.createdBy = requireEditor(editor);
        this.createdAt = DomainText.requireTime(createdAt, "Stage Definition creation time");
        this.updatedBy = editor;
        this.updatedAt = this.createdAt;
        this.version = 1L;
        this.revisionHistory = new ArrayList<WorkbenchStageDefinitionRevision>();
        this.draft = WorkbenchStageDraft.create(null, content, editor, createdAt);
    }

    private WorkbenchStageDefinition(
            String definitionIdentifier, WorkbenchStageDraft draft,
            WorkbenchStageDefinitionRevision currentPublishedRevision,
            List<WorkbenchStageDefinitionRevision> revisionHistory,
            boolean disabled, StageCatalogEditor createdBy, Instant createdAt,
            StageCatalogEditor updatedBy, Instant updatedAt, long version) {
        String identifier = requireIdentifier(definitionIdentifier);
        if (revisionHistory == null || revisionHistory.contains(null)
                || createdBy == null || updatedBy == null || version < 1L) {
            throw new IllegalStateException(
                    "persisted Stage Definition facts are invalid");
        }
        if (currentPublishedRevision == null && (disabled || !revisionHistory.isEmpty())) {
            throw new IllegalStateException(
                    "persisted Stage Definition Published Revision is inconsistent");
        }
        if (currentPublishedRevision != null
                && (revisionHistory.isEmpty()
                || revisionHistory.get(revisionHistory.size() - 1).getRevisionNumber()
                != currentPublishedRevision.getRevisionNumber())) {
            throw new IllegalStateException(
                    "persisted Stage Definition current Revision is inconsistent");
        }
        this.definitionIdentifier = identifier;
        this.draft = draft;
        this.currentPublishedRevision = currentPublishedRevision;
        this.revisionHistory = new ArrayList<WorkbenchStageDefinitionRevision>(
                revisionHistory);
        this.disabled = disabled;
        this.createdBy = createdBy;
        this.createdAt = DomainText.requireTime(
                createdAt, "Stage Definition creation time");
        this.updatedBy = updatedBy;
        this.updatedAt = DomainText.requireTime(
                updatedAt, "Stage Definition update time");
        this.version = version;
    }

    static WorkbenchStageDefinition create(
            String definitionIdentifier, WorkbenchStageDraftContent content,
            StageCatalogEditor editor, Instant createdAt) {
        if (content == null) {
            throw new StageCatalogException("WORKBENCH_STAGE_DEFINITION_INVALID",
                    "Stage Draft content is required");
        }
        return new WorkbenchStageDefinition(
                definitionIdentifier, content, editor, createdAt);
    }

    public static WorkbenchStageDefinition restore(
            String definitionIdentifier, WorkbenchStageDraft draft,
            WorkbenchStageDefinitionRevision currentPublishedRevision,
            List<WorkbenchStageDefinitionRevision> revisionHistory,
            boolean disabled, StageCatalogEditor createdBy, Instant createdAt,
            StageCatalogEditor updatedBy, Instant updatedAt, long version) {
        return new WorkbenchStageDefinition(
                definitionIdentifier, draft, currentPublishedRevision,
                revisionHistory, disabled, createdBy, createdAt,
                updatedBy, updatedAt, version);
    }

    public StageLifecycleStatus getLifecycleStatus() {
        if (disabled) {
            return StageLifecycleStatus.DISABLED;
        }
        return currentPublishedRevision == null
                ? StageLifecycleStatus.DRAFT : StageLifecycleStatus.PUBLISHED;
    }

    public boolean hasDraft() {
        return draft != null;
    }

    public List<WorkbenchStageDefinitionRevision> getRevisionHistory() {
        return Collections.unmodifiableList(
                new ArrayList<WorkbenchStageDefinitionRevision>(revisionHistory));
    }

    void saveDraft(
            long expectedVersion, WorkbenchStageDraftContent content,
            StageCatalogEditor editor, Instant savedAt) {
        requireVersion(expectedVersion);
        if (content == null) {
            throw new StageCatalogException("WORKBENCH_STAGE_DEFINITION_INVALID",
                    "Stage Draft content is required");
        }
        Long basedOn = currentPublishedRevision == null
                ? null : Long.valueOf(currentPublishedRevision.getRevisionNumber());
        this.draft = WorkbenchStageDraft.create(
                basedOn, content, requireEditor(editor), savedAt);
        change(editor, savedAt);
    }

    WorkbenchStageDefinitionRevision publish(
            long expectedVersion, ResolvedStageCapabilities resolved,
            StageCatalogEditor editor, Instant publishedAt) {
        requireVersion(expectedVersion);
        if (draft == null) {
            throw new StageCatalogException("WORKBENCH_STAGE_DEFINITION_INVALID",
                    "Stage Definition has no Draft to publish");
        }
        long nextRevision = revisionHistory.size() + 1L;
        WorkbenchStageDefinitionRevision published =
                WorkbenchStageDefinitionRevision.publish(
                        definitionIdentifier, nextRevision, draft.getContent(),
                        resolved, requireEditor(editor), publishedAt);
        revisionHistory.add(published);
        currentPublishedRevision = published;
        draft = null;
        disabled = false;
        change(editor, publishedAt);
        return published;
    }

    void disable(
            long expectedVersion, StageCatalogEditor editor, Instant disabledAt) {
        requireVersion(expectedVersion);
        if (currentPublishedRevision == null || disabled) {
            throw new StageCatalogException("WORKBENCH_STAGE_DEFINITION_INVALID",
                    "only a currently Published Stage can be disabled");
        }
        disabled = true;
        change(requireEditor(editor), disabledAt);
    }

    public void requireVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new StageCatalogException(
                    "WORKBENCH_STAGE_DEFINITION_VERSION_CONFLICT",
                    "Stage Definition version has changed");
        }
    }

    private void change(StageCatalogEditor editor, Instant changedAt) {
        this.updatedBy = requireEditor(editor);
        this.updatedAt = DomainText.requireTime(
                changedAt, "Stage Definition update time");
        this.version++;
    }

    private StageCatalogEditor requireEditor(StageCatalogEditor editor) {
        if (editor == null) {
            throw new IllegalArgumentException("Stage Catalog editor is required");
        }
        return editor;
    }

    private static String requireIdentifier(String definitionIdentifier) {
        String identifier = DomainText.require(
                definitionIdentifier, "Stage Definition identifier", 128);
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalStateException(
                    "persisted Stage Definition identifier is invalid");
        }
        return identifier;
    }
}
