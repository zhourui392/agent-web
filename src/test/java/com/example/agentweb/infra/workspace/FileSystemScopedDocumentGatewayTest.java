package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.document.DocumentContentView;
import com.example.agentweb.app.workbench.document.DocumentDirectoryQuery;
import com.example.agentweb.app.workbench.document.DocumentDirectoryView;
import com.example.agentweb.app.workbench.document.DocumentDownloadView;
import com.example.agentweb.app.workbench.document.DocumentFailureCode;
import com.example.agentweb.app.workbench.document.DocumentKind;
import com.example.agentweb.app.workbench.document.DocumentOperationException;
import com.example.agentweb.config.workbench.WorkbenchDocumentProperties;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import com.example.agentweb.infra.workspace.document.DocumentTypeResolver;
import com.example.agentweb.infra.workspace.document.FileSystemScopedDocumentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Repository Scope、运行期全局白名单和文档安全读取的组合测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class FileSystemScopedDocumentGatewayTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private Path repository;
    private RepositoryScope scope;
    private WorkspacePathPolicy pathPolicy;
    private WorkbenchDocumentProperties properties;
    private FileSystemScopedDocumentGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectories(tempDir.resolve("workspace"));
        repository = Files.createDirectories(workspace.resolve("service-api"));
        Files.createDirectories(repository.resolve(".git/objects"));
        scope = scope("service/api", repository);
        pathPolicy = mock(WorkspacePathPolicy.class);
        when(pathPolicy.requireExistingDirectory(anyString()))
                .thenAnswer(invocation -> Paths.get(invocation.getArgument(0, String.class))
                        .toRealPath().toString());
        when(pathPolicy.requireExistingFile(anyString()))
                .thenAnswer(invocation -> Paths.get(invocation.getArgument(0, String.class))
                        .toRealPath().toString());
        properties = new WorkbenchDocumentProperties();
        gateway = gateway(pathPolicy, properties);
    }

    @Test
    void listTreeShouldHideSensitiveAndNonRegularEntriesAndSortDirectoriesFirst()
            throws Exception {
        Files.createDirectories(repository.resolve("docs"));
        Files.createDirectories(repository.resolve("data"));
        Files.write(repository.resolve("data/secret.txt"), bytes("secret"));
        Files.write(repository.resolve(".env"), bytes("SECRET=value"));
        Files.write(repository.resolve(".gitignore"), bytes("target"));
        Files.write(repository.resolve("z.txt"), bytes("z"));
        Files.write(repository.resolve("a.txt"), bytes("aa"));
        Files.createSymbolicLink(repository.resolve("linked.txt"),
                repository.resolve("a.txt"));

        DocumentDirectoryView view = gateway.listTree(scope,
                new DocumentDirectoryQuery("service/api", "", 1000));

        List<String> names = view.getEntries().stream()
                .map(entry -> entry.getName()).collect(Collectors.toList());
        assertAll(
                () -> assertEquals("service/api", view.getRepositoryKey()),
                () -> assertEquals("", view.getPath()),
                () -> assertEquals(Arrays.asList("docs", ".gitignore", "a.txt", "z.txt"),
                        names),
                () -> assertEquals("DIRECTORY",
                        view.getEntries().get(0).getKind().name()),
                () -> assertEquals(Long.valueOf(2L),
                        view.getEntries().get(2).getSize()),
                () -> assertFalse(view.isTruncated()));
        verify(pathPolicy, atLeastOnce())
                .requireExistingDirectory(repository.toRealPath().toString());
    }

    @Test
    void listTreeShouldRespectSmallerRequestLimitAndReportTruncation() throws Exception {
        Files.write(repository.resolve("b.txt"), bytes("b"));
        Files.write(repository.resolve("a.txt"), bytes("a"));

        DocumentDirectoryView view = gateway.listTree(scope,
                new DocumentDirectoryQuery("service/api", "", 1));

        assertEquals(1, view.getEntries().size());
        assertEquals("a.txt", view.getEntries().get(0).getName());
        assertTrue(view.isTruncated());
    }

    @Test
    void readAndDownloadShouldReturnFullRawHashAndDefensiveSnapshot() throws Exception {
        byte[] text = "# 设计".getBytes(StandardCharsets.UTF_8);
        byte[] source = new byte[text.length + 3];
        source[0] = (byte) 0xef;
        source[1] = (byte) 0xbb;
        source[2] = (byte) 0xbf;
        System.arraycopy(text, 0, source, 3, text.length);
        Files.write(repository.resolve("README.md"), source);
        DocumentReference reference = DocumentReference.of(
                "service/api", "README.md");

        DocumentContentView content = gateway.readContent(scope, reference);
        DocumentDownloadView download = gateway.download(scope, reference);
        byte[] returned = download.getContent();
        returned[0] = 0;

        assertAll(
                () -> assertEquals(DocumentKind.MARKDOWN, content.getKind()),
                () -> assertEquals("text/markdown", content.getMediaType()),
                () -> assertEquals("UTF-8", content.getEncoding()),
                () -> assertEquals("# 设计", content.getContent()),
                () -> assertEquals(source.length, content.getSize()),
                () -> assertEquals(CanonicalHashing.sha256(source),
                        content.getContentVersion()),
                () -> assertFalse(content.isTruncated()),
                () -> assertFalse(content.isDeleted()),
                () -> assertEquals("README.md", download.getFileName()),
                () -> assertEquals("text/markdown", download.getMediaType()),
                () -> assertEquals(CanonicalHashing.sha256(source),
                        download.getContentVersion()),
                () -> assertArrayEquals(source, download.getContent()));
        verify(pathPolicy, atLeastOnce())
                .requireExistingFile(repository.resolve("README.md").toRealPath().toString());
    }

    @Test
    void readShouldUseKindSpecificBoundWithoutChangingFullSourceVersion() throws Exception {
        byte[] source = "你好-world".getBytes(StandardCharsets.UTF_8);
        Files.write(repository.resolve("notes.txt"), source);
        properties.setMaxTextBytes(4L);
        gateway = gateway(pathPolicy, properties);

        DocumentContentView content = gateway.readContent(scope,
                DocumentReference.of("service/api", "notes.txt"));

        assertAll(
                () -> assertEquals("你", content.getContent()),
                () -> assertTrue(content.isTruncated()),
                () -> assertEquals(source.length, content.getSize()),
                () -> assertEquals(CanonicalHashing.sha256(source),
                        content.getContentVersion()));
    }

    @Test
    void sensitiveSymlinkAndUnselectedPathsShouldFailAsNotFound() throws Exception {
        Files.write(repository.resolve(".env"), bytes("secret"));
        Files.createDirectories(repository.resolve("data"));
        Files.write(repository.resolve("data/secrets.properties"), bytes("secret"));
        Files.write(repository.resolve("safe.txt"), bytes("safe"));
        Files.createSymbolicLink(repository.resolve("linked.txt"),
                repository.resolve("safe.txt"));

        for (DocumentReference reference : Arrays.asList(
                DocumentReference.of("service/api", ".env"),
                DocumentReference.of("service/api", "data/secrets.properties"),
                DocumentReference.of("service/api", "linked.txt"),
                DocumentReference.of("other/service", "safe.txt"))) {
            DocumentOperationException failure = assertThrows(
                    DocumentOperationException.class,
                    () -> gateway.readContent(scope, reference));
            assertEquals(DocumentFailureCode.WORKBENCH_DOCUMENT_NOT_FOUND,
                    failure.getCode());
            assertFalse(failure.getMessage().contains(workspace.toString()));
        }
    }

    @Test
    void runtimeWorkspacePolicyDenialShouldRemainAStableWorkspaceFailure()
            throws Exception {
        Files.write(repository.resolve("README.md"), bytes("readme"));
        WorkspacePathPolicy denied = mock(WorkspacePathPolicy.class);
        when(denied.requireExistingFile(anyString()))
                .thenThrow(new IllegalArgumentException("denied absolute path"));
        FileSystemScopedDocumentGateway deniedGateway = gateway(denied, properties);

        WorkspaceOperationException failure = assertThrows(
                WorkspaceOperationException.class,
                () -> deniedGateway.readContent(scope,
                        DocumentReference.of("service/api", "README.md")));

        assertEquals(WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                failure.getCode());
        assertFalse(failure.getMessage().contains(repository.toString()));
    }

    @Test
    void downloadShouldRejectFileBeyondGlobalBound() throws Exception {
        Files.write(repository.resolve("large.bin"), bytes("12345"));
        properties.setMaxDownloadBytes(4L);
        properties.setMaxImageBytes(4L);
        properties.setMaxTextBytes(4L);
        properties.setMaxLogPreviewBytes(4L);
        gateway = gateway(pathPolicy, properties);

        DocumentOperationException failure = assertThrows(
                DocumentOperationException.class,
                () -> gateway.download(scope,
                        DocumentReference.of("service/api", "large.bin")));

        assertEquals(DocumentFailureCode.WORKBENCH_DOCUMENT_TOO_LARGE,
                failure.getCode());
    }

    @Test
    void inlineImageShouldReturnOnlyAllowlistedImageBytesWithinImageLimit()
            throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        Files.write(repository.resolve("diagram.png"), png);

        DocumentDownloadView inline = gateway.inlineImage(
                scope, DocumentReference.of("service/api", "diagram.png"));

        assertAll(
                () -> assertEquals("diagram.png", inline.getFileName()),
                () -> assertEquals("image/png", inline.getMediaType()),
                () -> assertEquals(CanonicalHashing.sha256(png),
                        inline.getContentVersion()),
                () -> assertArrayEquals(png, inline.getContent()));

        Files.write(repository.resolve("README.md"), bytes("not an image"));
        DocumentOperationException unsupported = assertThrows(
                DocumentOperationException.class,
                () -> gateway.inlineImage(scope,
                        DocumentReference.of("service/api", "README.md")));
        assertEquals(DocumentFailureCode.WORKBENCH_DOCUMENT_UNSUPPORTED,
                unsupported.getCode());
    }

    @Test
    void inlineImageShouldUseStricterImageLimitInsteadOfDownloadLimit()
            throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        Files.write(repository.resolve("large.png"), png);
        properties.setMaxImageBytes(4L);
        gateway = gateway(pathPolicy, properties);

        DocumentOperationException failure = assertThrows(
                DocumentOperationException.class,
                () -> gateway.inlineImage(scope,
                        DocumentReference.of("service/api", "large.png")));

        assertEquals(DocumentFailureCode.WORKBENCH_DOCUMENT_TOO_LARGE,
                failure.getCode());
    }

    private FileSystemScopedDocumentGateway gateway(
            WorkspacePathPolicy policy, WorkbenchDocumentProperties limits) {
        limits.validate();
        return new FileSystemScopedDocumentGateway(
                new ScopedPathResolver(), policy, limits,
                new DocumentTypeResolver());
    }

    private RepositoryScope scope(String repositoryKey, Path root) throws Exception {
        String fingerprint = WorkspaceFileSystemSecurity.rootFingerprint(root.toRealPath());
        ResolvedRepository resolved = ResolvedRepository.fromVerifiedFacts(
                repositoryKey, root.toRealPath().toString(), fingerprint, false);
        return RepositoryScope.create(
                workspace.toRealPath().toString(),
                RepositorySelection.of(repositoryKey,
                        Collections.singletonList(repositoryKey)),
                Collections.singletonList(resolved), 50);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
