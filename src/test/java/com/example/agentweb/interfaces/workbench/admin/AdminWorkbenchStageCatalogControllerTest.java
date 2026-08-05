package com.example.agentweb.interfaces.workbench.admin;

import com.example.agentweb.app.workbench.stage.WorkbenchStageCatalogAppService;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.auth.UserRole;
import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.StageCatalogException;
import com.example.agentweb.domain.workbench.stage.StageCommandReference;
import com.example.agentweb.domain.workbench.stage.StageCommandSelection;
import com.example.agentweb.domain.workbench.stage.StageMcpServerReference;
import com.example.agentweb.domain.workbench.stage.StageMcpServerSelection;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinition;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage Catalog 管理接口测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class AdminWorkbenchStageCatalogControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-05T08:00:00Z");

    private WorkbenchStageCatalogAppService appService;
    private UserContext userContext;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        appService = mock(WorkbenchStageCatalogAppService.class);
        userContext = mock(UserContext.class);
        when(userContext.currentUser()).thenReturn(Optional.of(
                new LoginUser("admin-1", "Admin", null, UserRole.ADMIN)));
        mvc = MockMvcBuilders.standaloneSetup(
                        new AdminWorkbenchStageCatalogController(appService, userContext))
                .setControllerAdvice(new AdminWorkbenchExceptionHandler())
                .build();
    }

    @Test
    void should_ListPublishedDefinitionWithIndependentDraftIndicator() throws Exception {
        // Given
        WorkbenchStageCatalog catalog = publishedCatalog();
        WorkbenchStageDefinition definition =
                catalog.requireDefinition("solution-design");
        catalog.saveDraft("solution-design", definition.getVersion(),
                draft(25, "技术方案 v2"), editor(), NOW.plusSeconds(60));
        when(appService.find()).thenReturn(catalog);

        // When / Then
        mvc.perform(get("/api/admin-settings/workbench/stage-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stageCatalogVersion").value(2))
                .andExpect(jsonPath("$.definitions[0].lifecycleStatus")
                        .value("PUBLISHED"))
                .andExpect(jsonPath("$.definitions[0].hasDraft").value(true))
                .andExpect(jsonPath("$.definitions[0].published.sequenceNumber")
                        .value(20))
                .andExpect(jsonPath("$.definitions[0].draft.sequenceNumber")
                        .value(25));
    }

    @Test
    void should_CreateAndSaveDraftWithExplicitVersionsAndAdministrator() throws Exception {
        // Given
        WorkbenchStageDefinition definition = draftCatalog()
                .requireDefinition("solution-design");
        when(appService.createDraft(eq("solution-design"), any(), eq(1L), any()))
                .thenReturn(definition);
        when(appService.saveDraft(eq("solution-design"), any(), eq(1L), any()))
                .thenReturn(definition);

        // When / Then
        mvc.perform(post("/api/admin-settings/workbench/stage-definitions")
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftRequest(true)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin-settings/workbench/stage-definitions/"
                        + "solution-design/draft")
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftRequest(false)))
                .andExpect(status().isOk());
        verify(appService).createDraft(eq("solution-design"), any(), eq(1L),
                eq(editor()));
        verify(appService).saveDraft(eq("solution-design"), any(), eq(1L),
                eq(editor()));
    }

    @Test
    void should_CreateDraft_When_OptionalDescriptionIsEmpty() throws Exception {
        // Given
        WorkbenchStageDefinition definition = draftCatalog()
                .requireDefinition("solution-design");
        when(appService.createDraft(eq("prd_analyze"),
                argThat(content -> content != null
                        && content.getDescription().isEmpty()),
                eq(1L), any())).thenReturn(definition);

        // When / Then
        mvc.perform(post("/api/admin-settings/workbench/stage-definitions")
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyDescriptionDraftRequest()))
                .andExpect(status().isOk());
        verify(appService).createDraft(eq("prd_analyze"),
                argThat(content -> content != null
                        && content.getDescription().isEmpty()),
                eq(1L), eq(editor()));
    }

    @Test
    void should_PublishAndDisableUsingDefinitionAndCatalogVersions() throws Exception {
        // Given
        WorkbenchStageCatalog catalog = publishedCatalog();
        WorkbenchStageDefinition definition =
                catalog.requireDefinition("solution-design");
        WorkbenchStageDefinitionRevision revision =
                definition.getCurrentPublishedRevision();
        when(appService.publishDraft("solution-design", 2L, 2L, editor()))
                .thenReturn(revision);
        when(appService.disable("solution-design", 2L, 2L, editor()))
                .thenReturn(definition);

        // When / Then
        mvc.perform(post("/api/admin-settings/workbench/stage-definitions/"
                        + "solution-design/publish")
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStageCatalogVersion\":2}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin-settings/workbench/stage-definitions/"
                        + "solution-design/disable")
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStageCatalogVersion\":2}"))
                .andExpect(status().isOk());
    }

    @Test
    void should_RejectNonAdminAndMapSequenceConflict() throws Exception {
        // Given
        when(appService.publishDraft(eq("solution-design"), eq(2L), eq(2L), any()))
                .thenThrow(new StageCatalogException(
                        "WORKBENCH_STAGE_SEQUENCE_CONFLICT", "conflict"));

        // When / Then
        mvc.perform(post("/api/admin-settings/workbench/stage-definitions/"
                        + "solution-design/publish")
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStageCatalogVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("WORKBENCH_STAGE_SEQUENCE_CONFLICT"));

        when(userContext.currentUser()).thenReturn(Optional.of(
                new LoginUser("user-1", "User", null, UserRole.USER)));
        mvc.perform(get("/api/admin-settings/workbench/stage-definitions"))
                .andExpect(status().isForbidden());
    }

    private WorkbenchStageCatalog draftCatalog() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        catalog.createDraft("solution-design", draft(20, "技术方案"),
                editor(), NOW);
        return catalog;
    }

    private WorkbenchStageCatalog publishedCatalog() {
        WorkbenchStageCatalog catalog = draftCatalog();
        catalog.publishDraft("solution-design", 1L, 1L,
                new ResolvedStageCapabilities(
                        Collections.singletonList(new StageCommandReference(
                                "architecture-review", "1.0.0",
                                CanonicalHashing.sha256("command"))),
                        Collections.emptyList(),
                        Collections.singletonList(new StageMcpServerReference(
                                "repository-query", "1.0.0",
                                CanonicalHashing.sha256("mcp"), false,
                                CapabilityAccess.READ, "STDIO"))),
                editor(), NOW.plusSeconds(30));
        return catalog;
    }

    private WorkbenchStageDraftContent draft(int sequence, String name) {
        return WorkbenchStageDraftContent.create(
                sequence, name, "阶段说明", "阶段规则",
                Set.of(RunMode.DISCUSS_READ_ONLY),
                Collections.singletonList(new StageCommandSelection(
                        "architecture-review", "1.0.0")),
                Collections.emptyList(),
                Collections.singletonList(new StageMcpServerSelection(
                        "repository-query", "1.0.0", false)));
    }

    private StageCatalogEditor editor() {
        return StageCatalogEditor.create("admin-1", "Admin");
    }

    private String draftRequest(boolean includeIdentifier) {
        return "{" + (includeIdentifier
                ? "\"definitionIdentifier\":\"solution-design\"," : "")
                + "\"sequenceNumber\":20,\"displayName\":\"技术方案\","
                + "\"description\":\"阶段说明\",\"stageRules\":\"阶段规则\","
                + "\"allowedRunModes\":[\"DISCUSS_READ_ONLY\"],"
                + "\"commandReferences\":[{\"identifier\":\"architecture-review\","
                + "\"version\":\"1.0.0\"}],\"skillReferences\":[],"
                + "\"mcpServerReferences\":[{\"identifier\":\"repository-query\","
                + "\"version\":\"1.0.0\",\"required\":false}]}";
    }

    private String emptyDescriptionDraftRequest() {
        return "{\"definitionIdentifier\":\"prd_analyze\","
                + "\"sequenceNumber\":1,\"displayName\":\"需求分析\","
                + "\"description\":\"\","
                + "\"stageRules\":\"按 DDD 事件风暴流程分析需求\","
                + "\"allowedRunModes\":[\"DISCUSS_READ_ONLY\"],"
                + "\"commandReferences\":[],\"skillReferences\":[],"
                + "\"mcpServerReferences\":[]}";
    }
}
