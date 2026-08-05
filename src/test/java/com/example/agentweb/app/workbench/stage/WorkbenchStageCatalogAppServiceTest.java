package com.example.agentweb.app.workbench.stage;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityArtifactIntegrityException;
import com.example.agentweb.domain.capability.CapabilityArtifactRegistry;
import com.example.agentweb.domain.capability.CommandCatalog;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.McpSecretReference;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.McpTransport;
import com.example.agentweb.domain.capability.SkillCatalog;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.StageCatalogException;
import com.example.agentweb.domain.workbench.stage.StageCommandSelection;
import com.example.agentweb.domain.workbench.stage.StageMcpServerSelection;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalogRepository;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 发布时 Capability 精确解析和 Artifact 归档应用编排测试。
 *
 * @author alex
 * @since 2026-08-05
 */
@ExtendWith(MockitoExtension.class)
class WorkbenchStageCatalogAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T08:00:00Z");
    private static final StageCatalogEditor ADMINISTRATOR =
            StageCatalogEditor.create("admin-1", "Alex");

    @Mock
    private WorkbenchStageCatalogRepository repository;
    @Mock
    private CommandCatalog commandCatalog;
    @Mock
    private SkillCatalog skillCatalog;
    @Mock
    private McpServerCatalog mcpServerCatalog;
    @Mock
    private CapabilityArtifactRegistry artifactRegistry;

    private WorkbenchStageCatalogAppService appService;

    @BeforeEach
    void setUp() {
        appService = new WorkbenchStageCatalogAppService(
                repository, commandCatalog, skillCatalog, mcpServerCatalog,
                artifactRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void should_ArchiveExactCapabilitiesBeforeSavingPublishedRevision() {
        // Given
        WorkbenchStageCatalog catalog = draftCatalog();
        CommandDefinition command = command();
        McpServerDefinition mcpServer = mcpServer();
        when(repository.find()).thenReturn(catalog);
        when(commandCatalog.discover()).thenReturn(Collections.singletonList(command));
        when(skillCatalog.discover()).thenReturn(Collections.emptyList());
        when(mcpServerCatalog.discover()).thenReturn(
                Collections.singletonList(mcpServer));

        // When
        WorkbenchStageDefinitionRevision published = appService.publishDraft(
                "solution-design", 1L, 1L, ADMINISTRATOR);

        // Then
        assertEquals(command.getContentHash(),
                published.getCommandReferences().get(0).getContentHash());
        assertEquals(mcpServer.getConfigurationHash(),
                published.getMcpServerReferences().get(0).getDefinitionHash());
        InOrder order = inOrder(artifactRegistry, repository);
        order.verify(artifactRegistry).archiveCommand(command);
        order.verify(artifactRegistry).archiveMcpServer(mcpServer);
        order.verify(repository).save(catalog, 1L, "solution-design", 1L);
    }

    @Test
    void should_NotPublishOrSave_When_SelectedCapabilityIsUnavailable() {
        // Given
        WorkbenchStageCatalog catalog = draftCatalog();
        when(repository.find()).thenReturn(catalog);
        when(commandCatalog.discover()).thenReturn(Collections.emptyList());
        when(skillCatalog.discover()).thenReturn(Collections.emptyList());
        when(mcpServerCatalog.discover()).thenReturn(
                Collections.singletonList(mcpServer()));

        // When / Then
        StageCatalogException failure = assertThrows(StageCatalogException.class,
                () -> appService.publishDraft(
                        "solution-design", 1L, 1L, ADMINISTRATOR));
        assertEquals("WORKBENCH_STAGE_CAPABILITY_UNAVAILABLE", failure.getCode());
        verify(artifactRegistry, never()).archiveMcpServer(
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).save(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void should_NotSaveCatalog_When_ArtifactArchiveFails() {
        // Given
        WorkbenchStageCatalog catalog = draftCatalog();
        CommandDefinition command = command();
        when(repository.find()).thenReturn(catalog);
        when(commandCatalog.discover()).thenReturn(Collections.singletonList(command));
        when(skillCatalog.discover()).thenReturn(Collections.emptyList());
        when(mcpServerCatalog.discover()).thenReturn(
                Collections.singletonList(mcpServer()));
        org.mockito.Mockito.doThrow(new CapabilityArtifactIntegrityException(
                        "WORKBENCH_CAPABILITY_ARTIFACT_INTEGRITY_FAILED", "broken"))
                .when(artifactRegistry).archiveCommand(command);

        // When / Then
        assertThrows(CapabilityArtifactIntegrityException.class,
                () -> appService.publishDraft(
                        "solution-design", 1L, 1L, ADMINISTRATOR));
        verify(repository, never()).save(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void should_Publish_WhenCapabilityCatalogsReturnJdkImmutableLists() {
        // Given
        WorkbenchStageCatalog catalog = draftCatalog();
        CommandDefinition command = command();
        McpServerDefinition mcpServer = mcpServer();
        when(repository.find()).thenReturn(catalog);
        when(commandCatalog.discover()).thenReturn(List.of(command));
        when(skillCatalog.discover()).thenReturn(List.of());
        when(mcpServerCatalog.discover()).thenReturn(List.of(mcpServer));

        // When
        WorkbenchStageDefinitionRevision published = appService.publishDraft(
                "solution-design", 1L, 1L, ADMINISTRATOR);

        // Then
        assertEquals("architecture-review",
                published.getCommandReferences().get(0).getIdentifier());
        verify(repository).save(catalog, 1L, "solution-design", 1L);
    }

    private WorkbenchStageCatalog draftCatalog() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        catalog.createDraft("solution-design", WorkbenchStageDraftContent.create(
                        20, "技术方案", "阶段说明", "遵循阶段规则",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.singletonList(new StageCommandSelection(
                                "architecture-review", "1.0.0")),
                        Collections.emptyList(),
                        Collections.singletonList(new StageMcpServerSelection(
                                "repository-query", "1.0.0", false))),
                ADMINISTRATOR, NOW.minusSeconds(60));
        return catalog;
    }

    private CommandDefinition command() {
        return CommandDefinition.create(
                "architecture-review", "1.0.0", "Architecture Review",
                "Review architecture", "<target>", "review $ARGUMENTS",
                "platform-commands", NOW.minusSeconds(120));
    }

    private McpServerDefinition mcpServer() {
        return McpServerDefinition.managed(
                "repository-query", "1.0.0", "Repository Query",
                "Query repositories", Set.of("CODEX"),
                java.util.List.of("repository-query", "--stdio"),
                Collections.<McpSecretReference>emptyList(), McpTransport.STDIO,
                "/opt/agent-tools/repository-query", "", CapabilityAccess.READ,
                10, 30, CanonicalHashing.sha256("mcp"));
    }
}
