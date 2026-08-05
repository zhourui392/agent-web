package com.example.agentweb.app.workbench.document;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.document.port.ScopedDocumentGateway;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workbench Document 查询的 Owner、冻结 Scope 与 Gateway 编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchDocumentAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");
    private static final OwnerReference OTHER = OwnerReference.of("user-2", "Other");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");

    private WorkbenchRepository workbenchRepository;
    private ScopedDocumentGateway gateway;
    private WorkbenchTelemetry telemetry;
    private WorkbenchDocumentAppService service;
    private Workbench workbench;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        gateway = mock(ScopedDocumentGateway.class);
        telemetry = mock(WorkbenchTelemetry.class);
        service = new WorkbenchDocumentAppService(
                workbenchRepository, gateway, telemetry);
        workbench = workbench();
    }

    @Test
    void givenOwnedWorkbenchWhenQueryDocumentsThenDelegateFrozenScopeUnchanged() {
        DocumentDirectoryQuery directory = new DocumentDirectoryQuery(
                "agent-web", "", 100);
        DocumentReference document = DocumentReference.of(
                "agent-web", "README.md");
        DocumentDirectoryView tree = mock(DocumentDirectoryView.class);
        DocumentContentView content = mock(DocumentContentView.class);
        when(content.getKind()).thenReturn(DocumentKind.MARKDOWN);
        DocumentDownloadView download = mock(DocumentDownloadView.class);
        DocumentDownloadView inlineImage = mock(DocumentDownloadView.class);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(gateway.listTree(same(workbench.getRepositoryScope()), same(directory)))
                .thenReturn(tree);
        when(gateway.readContent(same(workbench.getRepositoryScope()), same(document)))
                .thenReturn(content);
        when(gateway.download(same(workbench.getRepositoryScope()), same(document)))
                .thenReturn(download);
        when(gateway.inlineImage(
                same(workbench.getRepositoryScope()), same(document)))
                .thenReturn(inlineImage);

        assertSame(tree, service.listTree(OWNER, WORKBENCH_ID, directory));
        assertSame(content, service.readContent(OWNER, WORKBENCH_ID, document));
        assertSame(download, service.download(OWNER, WORKBENCH_ID, document));
        assertSame(inlineImage,
                service.inlineImage(OWNER, WORKBENCH_ID, document));

        verify(gateway).listTree(same(workbench.getRepositoryScope()), same(directory));
        verify(gateway).readContent(same(workbench.getRepositoryScope()), same(document));
        verify(gateway).download(same(workbench.getRepositoryScope()), same(document));
        verify(gateway).inlineImage(
                same(workbench.getRepositoryScope()), same(document));
        verify(telemetry).documentRead(DocumentKind.MARKDOWN, "SUCCESS");
        verify(workbenchRepository, never()).update(workbench);
    }

    @Test
    void givenMissingOrForeignWorkbenchWhenQueryThenObscureAndNeverReadFiles() {
        DocumentDirectoryQuery query = new DocumentDirectoryQuery(
                "agent-web", "", 10);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.empty());
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.listTree(OWNER, WORKBENCH_ID, query));
        verifyNoInteractions(gateway);

        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.listTree(OTHER, WORKBENCH_ID, query));
        verifyNoInteractions(gateway);
    }

    @Test
    void givenArchivedOwnedWorkbenchWhenReadThenRemainAvailable() {
        workbench.archive(OWNER, NOW.plusSeconds(1));
        DocumentReference reference = DocumentReference.of(
                "agent-web", "README.md");
        DocumentContentView expected = mock(DocumentContentView.class);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(gateway.readContent(
                same(workbench.getRepositoryScope()), same(reference)))
                .thenReturn(expected);

        assertSame(expected,
                service.readContent(OWNER, WORKBENCH_ID, reference));
    }

    @Test
    void givenGatewayFailureWhenReadThenPreserveStableFailure() {
        DocumentReference reference = DocumentReference.of(
                "agent-web", "README.md");
        IllegalStateException failure = new IllegalStateException("stable failure");
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(gateway.readContent(
                same(workbench.getRepositoryScope()), same(reference)))
                .thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.readContent(OWNER, WORKBENCH_ID, reference)));
        verify(telemetry).documentRead(null, "FAILED");
    }

    @Test
    void givenScopeViolationWhenReadThenRecordSecurityBoundaryFact() {
        DocumentReference reference = DocumentReference.of(
                "agent-web", "README.md");
        WorkspaceOperationException failure = new WorkspaceOperationException(
                WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION,
                "document is outside repository scope");
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(gateway.readContent(
                same(workbench.getRepositoryScope()), same(reference)))
                .thenThrow(failure);

        assertSame(failure, assertThrows(WorkspaceOperationException.class,
                () -> service.readContent(OWNER, WORKBENCH_ID, reference)));
        verify(telemetry).workspaceScopeViolation();
        verify(telemetry).documentRead(null, "FAILED");
    }

    @Test
    void givenForbiddenWorkspacePathWhenReadThenRecordScopeSecurityFact() {
        DocumentReference reference = DocumentReference.of(
                "agent-web", "README.md");
        WorkspaceOperationException failure = new WorkspaceOperationException(
                WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                "document path is forbidden");
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(gateway.readContent(
                same(workbench.getRepositoryScope()), same(reference)))
                .thenThrow(failure);

        assertSame(failure, assertThrows(WorkspaceOperationException.class,
                () -> service.readContent(OWNER, WORKBENCH_ID, reference)));
        verify(telemetry).workspaceScopeViolation();
        verify(telemetry).documentRead(null, "FAILED");
    }

    private static Workbench workbench() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(ResolvedRepository.fromVerifiedFacts(
                        "agent-web", "/workspace/agent-web", repeat('1'), false)),
                50);
        WorkspaceSnapshotReference snapshot = new WorkspaceSnapshotReference(
                "snapshot-1",
                com.example.agentweb.domain.workspace.WorkspaceTopology.of(
                        "/workspace", selection).getTopologyHash(),
                repeat('2'), 1);
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "Read documents",
                AgentType.CODEX, "local", scope, snapshot,
                Collections.singletonList(stageState()), NOW);
    }

    private static WorkbenchStageState stageState() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor editor = StageCatalogEditor.create(
                "admin-1", "Admin");
        catalog.createDraft(
                "document-review",
                WorkbenchStageDraftContent.create(
                        10, "文档审阅", "读取仓库文档", "仅在冻结范围内读取",
                        Collections.singleton(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                editor, NOW.minusSeconds(1));
        WorkbenchStageDefinitionRevision revision = catalog.publishDraft(
                "document-review", catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                editor, NOW);
        return WorkbenchStageState.initial(
                "stage-document-review",
                WorkbenchStageSnapshot.fromPublishedRevision(revision));
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        Arrays.fill(values, value);
        return new String(values);
    }
}
