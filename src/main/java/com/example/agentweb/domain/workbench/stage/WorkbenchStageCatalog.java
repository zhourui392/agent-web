package com.example.agentweb.domain.workbench.stage;

import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态 Stage Definition、发布顺序和 Catalog Version 的一致性边界。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageCatalog {

    private static final int MAX_DEFINITIONS = 32;

    private final Map<String, WorkbenchStageDefinition> definitions;
    private long catalogVersion;
    private Instant updatedAt;

    private WorkbenchStageCatalog() {
        this.definitions = new LinkedHashMap<String, WorkbenchStageDefinition>();
        this.catalogVersion = 1L;
    }

    private WorkbenchStageCatalog(
            long catalogVersion, Instant updatedAt,
            List<WorkbenchStageDefinition> restoredDefinitions) {
        if (catalogVersion < 1L || restoredDefinitions == null
                || restoredDefinitions.size() > MAX_DEFINITIONS
                || restoredDefinitions.contains(null)) {
            throw new IllegalStateException("persisted Stage Catalog facts are invalid");
        }
        this.definitions = new LinkedHashMap<String, WorkbenchStageDefinition>();
        for (WorkbenchStageDefinition definition : restoredDefinitions) {
            if (definitions.put(definition.getDefinitionIdentifier(), definition) != null) {
                throw new IllegalStateException(
                        "persisted Stage Definition identifiers are duplicated");
            }
        }
        this.catalogVersion = catalogVersion;
        this.updatedAt = updatedAt;
        requirePersistedSequenceUniqueness();
    }

    public static WorkbenchStageCatalog empty() {
        return new WorkbenchStageCatalog();
    }

    public static WorkbenchStageCatalog restore(
            long catalogVersion, Instant updatedAt,
            List<WorkbenchStageDefinition> definitions) {
        return new WorkbenchStageCatalog(catalogVersion, updatedAt, definitions);
    }

    public List<WorkbenchStageDefinition> getDefinitions() {
        List<WorkbenchStageDefinition> result =
                new ArrayList<WorkbenchStageDefinition>(definitions.values());
        result.sort(Comparator.comparing(
                WorkbenchStageDefinition::getDefinitionIdentifier));
        return Collections.unmodifiableList(result);
    }

    public WorkbenchStageDefinition requireDefinition(String definitionIdentifier) {
        WorkbenchStageDefinition definition = definitions.get(definitionIdentifier);
        if (definition == null) {
            throw new StageCatalogException(
                    "WORKBENCH_STAGE_DEFINITION_NOT_FOUND",
                    "Stage Definition was not found");
        }
        return definition;
    }

    public void createDraft(
            String definitionIdentifier, WorkbenchStageDraftContent content,
            StageCatalogEditor editor, Instant createdAt) {
        if (definitions.size() >= MAX_DEFINITIONS) {
            throw new StageCatalogException("WORKBENCH_STAGE_DEFINITION_INVALID",
                    "Stage Definition limit was reached");
        }
        WorkbenchStageDefinition created = WorkbenchStageDefinition.create(
                definitionIdentifier, content, editor, createdAt);
        if (definitions.putIfAbsent(definitionIdentifier, created) != null) {
            throw new StageCatalogException("WORKBENCH_STAGE_DEFINITION_INVALID",
                    "Stage Definition identifier already exists");
        }
        updatedAt = createdAt;
    }

    public void saveDraft(
            String definitionIdentifier, long expectedDefinitionVersion,
            WorkbenchStageDraftContent content,
            StageCatalogEditor editor, Instant savedAt) {
        requireDefinition(definitionIdentifier).saveDraft(
                expectedDefinitionVersion, content, editor, savedAt);
        updatedAt = savedAt;
    }

    public WorkbenchStageDefinitionRevision publishDraft(
            String definitionIdentifier, long expectedCatalogVersion,
            long expectedDefinitionVersion, ResolvedStageCapabilities resolved,
            StageCatalogEditor editor, Instant publishedAt) {
        requireCatalogVersion(expectedCatalogVersion);
        WorkbenchStageDefinition definition = requireDefinition(definitionIdentifier);
        definition.requireVersion(expectedDefinitionVersion);
        if (!definition.hasDraft()) {
            throw new StageCatalogException("WORKBENCH_STAGE_DEFINITION_INVALID",
                    "Stage Definition has no Draft to publish");
        }
        requireUniqueSequence(definitionIdentifier,
                definition.getDraft().getContent().getSequenceNumber());
        WorkbenchStageDefinitionRevision published = definition.publish(
                expectedDefinitionVersion, resolved, editor, publishedAt);
        catalogVersion++;
        updatedAt = publishedAt;
        return published;
    }

    public void disable(
            String definitionIdentifier, long expectedCatalogVersion,
            long expectedDefinitionVersion, StageCatalogEditor editor,
            Instant disabledAt) {
        requireCatalogVersion(expectedCatalogVersion);
        WorkbenchStageDefinition definition = requireDefinition(definitionIdentifier);
        definition.disable(expectedDefinitionVersion, editor, disabledAt);
        catalogVersion++;
        updatedAt = disabledAt;
    }

    public List<WorkbenchStageDefinitionRevision> selectableRevisions() {
        List<WorkbenchStageDefinitionRevision> selectable =
                new ArrayList<WorkbenchStageDefinitionRevision>();
        for (WorkbenchStageDefinition definition : definitions.values()) {
            if (definition.getLifecycleStatus() == StageLifecycleStatus.PUBLISHED) {
                selectable.add(definition.getCurrentPublishedRevision());
            }
        }
        selectable.sort(Comparator
                .comparingInt(WorkbenchStageDefinitionRevision::getSequenceNumber));
        return Collections.unmodifiableList(selectable);
    }

    public List<WorkbenchStageDefinitionRevision> selectPublishedRevisions(
            List<String> definitionIdentifiers, long expectedCatalogVersion) {
        if (catalogVersion != expectedCatalogVersion) {
            throw new StageCatalogException(
                    "WORKBENCH_STAGE_CATALOG_CHANGED",
                    "Stage Catalog changed after the selection was displayed");
        }
        if (definitionIdentifiers == null || definitionIdentifiers.isEmpty()) {
            throw new StageCatalogException(
                    "WORKBENCH_STAGE_SELECTION_EMPTY",
                    "at least one Stage Definition must be selected");
        }
        java.util.Set<String> selectedIdentifiers =
                new java.util.HashSet<String>();
        List<WorkbenchStageDefinitionRevision> selected =
                new ArrayList<WorkbenchStageDefinitionRevision>();
        for (String definitionIdentifier : definitionIdentifiers) {
            if (definitionIdentifier == null) {
                throw notSelectable();
            }
            if (!selectedIdentifiers.add(definitionIdentifier)) {
                throw new StageCatalogException(
                        "WORKBENCH_STAGE_SELECTION_DUPLICATED",
                        "Stage Definition selection must not contain duplicates");
            }
            WorkbenchStageDefinition definition = definitions.get(definitionIdentifier);
            if (definition == null
                    || definition.getLifecycleStatus()
                    != StageLifecycleStatus.PUBLISHED) {
                throw notSelectable();
            }
            selected.add(definition.getCurrentPublishedRevision());
        }
        selected.sort(Comparator.comparingInt(
                WorkbenchStageDefinitionRevision::getSequenceNumber));
        return Collections.unmodifiableList(selected);
    }

    public void requireCatalogVersion(long expectedCatalogVersion) {
        if (catalogVersion != expectedCatalogVersion) {
            throw new StageCatalogException(
                    "WORKBENCH_STAGE_CATALOG_VERSION_CONFLICT",
                    "Stage Catalog version has changed");
        }
    }

    private void requireUniqueSequence(
            String currentDefinitionIdentifier, int candidateSequence) {
        for (WorkbenchStageDefinition definition : definitions.values()) {
            if (!definition.getDefinitionIdentifier().equals(currentDefinitionIdentifier)
                    && definition.getLifecycleStatus() == StageLifecycleStatus.PUBLISHED
                    && definition.getCurrentPublishedRevision().getSequenceNumber()
                    == candidateSequence) {
                throw new StageCatalogException(
                        "WORKBENCH_STAGE_SEQUENCE_CONFLICT",
                        "Published Stage sequence number must be unique");
            }
        }
    }

    private void requirePersistedSequenceUniqueness() {
        java.util.Set<Integer> sequences = new java.util.HashSet<Integer>();
        for (WorkbenchStageDefinition definition : definitions.values()) {
            if (definition.getLifecycleStatus() == StageLifecycleStatus.PUBLISHED
                    && !sequences.add(definition.getCurrentPublishedRevision()
                    .getSequenceNumber())) {
                throw new IllegalStateException(
                        "persisted Published Stage sequences are duplicated");
            }
        }
    }

    private StageCatalogException notSelectable() {
        return new StageCatalogException(
                "WORKBENCH_STAGE_NOT_SELECTABLE",
                "selected Stage Definition is not currently Published");
    }
}
