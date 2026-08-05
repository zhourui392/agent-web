package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.RunMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态 Workbench Stage Catalog 聚合测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchStageCatalogTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-05T08:00:00Z");
    private static final StageCatalogEditor ADMINISTRATOR =
            StageCatalogEditor.create("admin-1", "Alex");

    @Test
    void should_KeepDraftOutOfPublishedSelection_WithoutChangingCatalogVersion() {
        // Given
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        long initialCatalogVersion = catalog.getCatalogVersion();

        // When
        catalog.createDraft("requirement-analysis", draft(10, "需求分析"),
                ADMINISTRATOR, CREATED_AT);

        // Then
        WorkbenchStageDefinition definition =
                catalog.requireDefinition("requirement-analysis");
        assertEquals(StageLifecycleStatus.DRAFT, definition.getLifecycleStatus());
        assertTrue(definition.hasDraft());
        assertTrue(catalog.selectableRevisions().isEmpty());
        assertEquals(initialCatalogVersion, catalog.getCatalogVersion());
    }

    @Test
    void should_AcceptEmptyDescription_When_OptionalMetadataIsOmitted() {
        // Given
        WorkbenchStageDraftContent content = WorkbenchStageDraftContent.create(
                1, "需求分析", "", "按 DDD 事件风暴流程分析需求",
                Set.of(RunMode.DISCUSS_READ_ONLY), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        // When
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        catalog.createDraft("prd_analyze", content, ADMINISTRATOR, CREATED_AT);

        // Then
        assertEquals("", catalog.requireDefinition("prd_analyze")
                .getDraft().getContent().getDescription());
    }

    @Test
    void should_PublishImmutableRevision_AndKeepItEffectiveWhileNewDraftIsEdited() {
        // Given
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        catalog.createDraft("solution-design", draft(20, "技术方案"),
                ADMINISTRATOR, CREATED_AT);
        WorkbenchStageDefinition definition =
                catalog.requireDefinition("solution-design");

        // When
        catalog.publishDraft("solution-design", catalog.getCatalogVersion(),
                definition.getVersion(), resolvedReadOnlyCapabilities(),
                ADMINISTRATOR, CREATED_AT.plusSeconds(60));
        WorkbenchStageDefinitionRevision published =
                catalog.requireDefinition("solution-design").getCurrentPublishedRevision();
        long publishedCatalogVersion = catalog.getCatalogVersion();
        long publishedDefinitionVersion =
                catalog.requireDefinition("solution-design").getVersion();
        catalog.saveDraft("solution-design", publishedDefinitionVersion,
                draft(25, "技术方案 v2"), ADMINISTRATOR,
                CREATED_AT.plusSeconds(120));

        // Then
        WorkbenchStageDefinition edited =
                catalog.requireDefinition("solution-design");
        assertEquals(StageLifecycleStatus.PUBLISHED, edited.getLifecycleStatus());
        assertTrue(edited.hasDraft());
        assertEquals(publishedCatalogVersion, catalog.getCatalogVersion());
        assertEquals(1L, published.getRevisionNumber());
        assertEquals("技术方案", published.getDisplayName());
        assertEquals(20, published.getSequenceNumber());
        assertEquals(published.getDefinitionHash(),
                edited.getCurrentPublishedRevision().getDefinitionHash());
        assertNotEquals(edited.getDraft().getDraftHash(), published.getDefinitionHash());
        assertFalse(catalog.selectableRevisions().isEmpty());
    }

    @Test
    void should_EnforceUniquePublishedOrder_AndRestoreDisabledStageByPublishingDraft() {
        // Given
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        publishNew(catalog, "requirement-analysis", 10, "需求分析");
        catalog.createDraft("solution-design", draft(10, "技术方案"),
                ADMINISTRATOR, CREATED_AT.plusSeconds(180));

        // When / Then
        StageCatalogException conflict = assertThrows(StageCatalogException.class,
                () -> catalog.publishDraft("solution-design",
                        catalog.getCatalogVersion(),
                        catalog.requireDefinition("solution-design").getVersion(),
                        resolvedReadOnlyCapabilities(), ADMINISTRATOR,
                        CREATED_AT.plusSeconds(240)));
        assertEquals("WORKBENCH_STAGE_SEQUENCE_CONFLICT", conflict.getCode());

        WorkbenchStageDefinition requirement =
                catalog.requireDefinition("requirement-analysis");
        catalog.disable("requirement-analysis", catalog.getCatalogVersion(),
                requirement.getVersion(), ADMINISTRATOR,
                CREATED_AT.plusSeconds(300));
        assertEquals(StageLifecycleStatus.DISABLED,
                catalog.requireDefinition("requirement-analysis").getLifecycleStatus());
        assertTrue(catalog.selectableRevisions().isEmpty());

        WorkbenchStageDefinition disabled =
                catalog.requireDefinition("requirement-analysis");
        catalog.saveDraft("requirement-analysis", disabled.getVersion(),
                draft(15, "需求分析恢复"), ADMINISTRATOR,
                CREATED_AT.plusSeconds(360));
        catalog.publishDraft("requirement-analysis", catalog.getCatalogVersion(),
                catalog.requireDefinition("requirement-analysis").getVersion(),
                resolvedReadOnlyCapabilities(), ADMINISTRATOR,
                CREATED_AT.plusSeconds(420));
        assertEquals(StageLifecycleStatus.PUBLISHED,
                catalog.requireDefinition("requirement-analysis").getLifecycleStatus());
        assertEquals(2L, catalog.requireDefinition("requirement-analysis")
                .getCurrentPublishedRevision().getRevisionNumber());
    }

    @Test
    void should_RejectStaleCatalogOrDefinitionVersion() {
        // Given
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        catalog.createDraft("implement-test", draft(30, "开发测试"),
                ADMINISTRATOR, CREATED_AT);
        WorkbenchStageDefinition definition =
                catalog.requireDefinition("implement-test");

        // When / Then
        assertEquals("WORKBENCH_STAGE_CATALOG_VERSION_CONFLICT",
                assertThrows(StageCatalogException.class,
                        () -> catalog.publishDraft("implement-test",
                                catalog.getCatalogVersion() + 1,
                                definition.getVersion(),
                                resolvedReadOnlyCapabilities(), ADMINISTRATOR,
                                CREATED_AT.plusSeconds(60))).getCode());
        assertEquals("WORKBENCH_STAGE_DEFINITION_VERSION_CONFLICT",
                assertThrows(StageCatalogException.class,
                        () -> catalog.saveDraft("implement-test",
                                definition.getVersion() + 1,
                                draft(30, "开发测试"), ADMINISTRATOR,
                                CREATED_AT.plusSeconds(60))).getCode());
    }

    @Test
    void should_RejectWriteMcpAndCapabilityMismatch_When_StageIsReadOnly() {
        // Given
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        catalog.createDraft("security-review", draft(40, "安全审查"),
                ADMINISTRATOR, CREATED_AT);
        WorkbenchStageDefinition definition =
                catalog.requireDefinition("security-review");
        ResolvedStageCapabilities writeMcp = new ResolvedStageCapabilities(
                Collections.singletonList(new StageCommandReference(
                        "architecture-review", "1.0.0", hash("command"))),
                Collections.emptyList(),
                Collections.singletonList(new StageMcpServerReference(
                        "repository-query", "1.0.0", hash("mcp"), false,
                        CapabilityAccess.WRITE, "STDIO")));

        // When / Then
        assertEquals("WORKBENCH_STAGE_RUNTIME_INCOMPATIBLE",
                assertThrows(StageCatalogException.class,
                        () -> catalog.publishDraft("security-review",
                                catalog.getCatalogVersion(), definition.getVersion(),
                                writeMcp, ADMINISTRATOR,
                                CREATED_AT.plusSeconds(60))).getCode());

        ResolvedStageCapabilities missingCommand = new ResolvedStageCapabilities(
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(new StageMcpServerReference(
                        "repository-query", "1.0.0", hash("mcp"), false,
                        CapabilityAccess.READ, "STDIO")));
        assertEquals("WORKBENCH_STAGE_CAPABILITY_UNAVAILABLE",
                assertThrows(StageCatalogException.class,
                        () -> catalog.publishDraft("security-review",
                                catalog.getCatalogVersion(), definition.getVersion(),
                                missingCommand, ADMINISTRATOR,
                                CREATED_AT.plusSeconds(60))).getCode());
    }

    @Test
    void should_SelectPublishedRevisionsByServerOrderAndRejectInvalidSelections() {
        // Given
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        publishNew(catalog, "implementation", 30, "开发测试");
        publishNew(catalog, "requirement-analysis", 10, "需求分析");

        // When
        List<WorkbenchStageDefinitionRevision> selected =
                catalog.selectPublishedRevisions(
                        List.of("implementation", "requirement-analysis"),
                        catalog.getCatalogVersion());

        // Then
        assertEquals(List.of("requirement-analysis", "implementation"),
                selected.stream()
                        .map(WorkbenchStageDefinitionRevision::getDefinitionIdentifier)
                        .toList());
        assertEquals("WORKBENCH_STAGE_SELECTION_EMPTY",
                assertThrows(StageCatalogException.class,
                        () -> catalog.selectPublishedRevisions(
                                Collections.emptyList(), catalog.getCatalogVersion()))
                        .getCode());
        assertEquals("WORKBENCH_STAGE_SELECTION_DUPLICATED",
                assertThrows(StageCatalogException.class,
                        () -> catalog.selectPublishedRevisions(
                                List.of("implementation", "implementation"),
                                catalog.getCatalogVersion())).getCode());
        assertEquals("WORKBENCH_STAGE_NOT_SELECTABLE",
                assertThrows(StageCatalogException.class,
                        () -> catalog.selectPublishedRevisions(
                                List.of("unknown"), catalog.getCatalogVersion()))
                        .getCode());
        assertEquals("WORKBENCH_STAGE_CATALOG_CHANGED",
                assertThrows(StageCatalogException.class,
                        () -> catalog.selectPublishedRevisions(
                                List.of("implementation"),
                                catalog.getCatalogVersion() - 1)).getCode());
    }

    private void publishNew(
            WorkbenchStageCatalog catalog, String identifier,
            int sequenceNumber, String displayName) {
        catalog.createDraft(identifier, draft(sequenceNumber, displayName),
                ADMINISTRATOR, CREATED_AT);
        catalog.publishDraft(identifier, catalog.getCatalogVersion(),
                catalog.requireDefinition(identifier).getVersion(),
                resolvedReadOnlyCapabilities(), ADMINISTRATOR,
                CREATED_AT.plusSeconds(60));
    }

    private WorkbenchStageDraftContent draft(int sequenceNumber, String displayName) {
        return WorkbenchStageDraftContent.create(
                sequenceNumber, displayName, "阶段说明", "遵循阶段规则",
                Set.of(RunMode.DISCUSS_READ_ONLY),
                Collections.singletonList(new StageCommandSelection(
                        "architecture-review", "1.0.0")),
                Collections.emptyList(),
                Collections.singletonList(new StageMcpServerSelection(
                        "repository-query", "1.0.0", false)));
    }

    private ResolvedStageCapabilities resolvedReadOnlyCapabilities() {
        return new ResolvedStageCapabilities(
                Collections.singletonList(new StageCommandReference(
                        "architecture-review", "1.0.0", hash("command"))),
                Collections.emptyList(),
                Collections.singletonList(new StageMcpServerReference(
                        "repository-query", "1.0.0", hash("mcp"), false,
                        CapabilityAccess.READ, "STDIO")));
    }

    private String hash(String value) {
        return CanonicalHashing.sha256(value);
    }
}
