package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.stage.WorkbenchStageCatalogAppService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinition;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 普通用户可选 Stage Definition 查询接口测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchStageDefinitionControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-05T08:00:00Z");
    private static final StageCatalogEditor ADMINISTRATOR =
            StageCatalogEditor.create("admin-1", "Admin");

    private WorkbenchStageCatalogAppService appService;
    private CurrentUserProvider currentUserProvider;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        appService = mock(WorkbenchStageCatalogAppService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.currentUserId()).thenReturn("user-1");
        mvc = MockMvcBuilders.standaloneSetup(
                        new WorkbenchStageDefinitionController(
                                appService, currentUserProvider))
                .build();
    }

    @Test
    void should_ReturnOnlySelectablePublishedRevisionsInServerSequence() throws Exception {
        // Given
        WorkbenchStageCatalog catalog = catalogWithDraftAndDisabledStage();
        when(appService.find()).thenReturn(catalog);

        // When / Then
        mvc.perform(get("/api/workbench/stage-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stageCatalogVersion").value(5))
                .andExpect(jsonPath("$.stages.length()").value(2))
                .andExpect(jsonPath("$.stages[0].definitionIdentifier")
                        .value("requirement-analysis"))
                .andExpect(jsonPath("$.stages[0].publishedRevision").value(1))
                .andExpect(jsonPath("$.stages[0].sequenceNumber").value(10))
                .andExpect(jsonPath("$.stages[0].displayName").value("需求分析"))
                .andExpect(jsonPath("$.stages[0].definitionHash").isString())
                .andExpect(jsonPath("$.stages[0].stageRules").doesNotExist())
                .andExpect(jsonPath("$.stages[0].commandReferences").doesNotExist())
                .andExpect(jsonPath("$.stages[1].definitionIdentifier")
                        .value("implementation"))
                .andExpect(jsonPath("$.stages[1].sequenceNumber").value(30));
        verify(currentUserProvider).currentUserId();
    }

    private WorkbenchStageCatalog catalogWithDraftAndDisabledStage() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        publish(catalog, "requirement-analysis", 10, "需求分析");
        publish(catalog, "implementation", 30, "开发测试");
        publish(catalog, "disabled-stage", 20, "停用阶段");
        WorkbenchStageDefinition disabled =
                catalog.requireDefinition("disabled-stage");
        catalog.disable("disabled-stage", 4L, disabled.getVersion(),
                ADMINISTRATOR, NOW.plusSeconds(30));
        WorkbenchStageDefinition requirement =
                catalog.requireDefinition("requirement-analysis");
        catalog.saveDraft("requirement-analysis", requirement.getVersion(),
                content(15, "需求分析草稿"), ADMINISTRATOR, NOW.plusSeconds(40));
        return catalog;
    }

    private void publish(
            WorkbenchStageCatalog catalog, String identifier,
            int sequenceNumber, String displayName) {
        catalog.createDraft(identifier, content(sequenceNumber, displayName),
                ADMINISTRATOR, NOW);
        WorkbenchStageDefinition definition = catalog.requireDefinition(identifier);
        catalog.publishDraft(identifier, catalog.getCatalogVersion(),
                definition.getVersion(), new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                ADMINISTRATOR, NOW.plusSeconds(10));
    }

    private WorkbenchStageDraftContent content(
            int sequenceNumber, String displayName) {
        return WorkbenchStageDraftContent.create(
                sequenceNumber, displayName, "阶段说明", "阶段规则",
                Set.of(RunMode.DISCUSS_READ_ONLY), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }
}
