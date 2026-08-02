package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeAttachmentExpectation;
import com.example.agentweb.app.workbench.attachment.port.StoredUploadedAttachment;
import com.example.agentweb.app.workbench.attachment.port.UploadedAttachmentStorageRequest;
import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runtime 启动前附件 exact hash、文件身份和 no-symlink 重校验。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeAttachmentVerifierTest {

    @TempDir
    Path tempDir;

    @Test
    void unchangedRegularAttachmentShouldPassWithoutRetainingContent() throws Exception {
        Path repository = Files.createDirectory(tempDir.resolve("repository"));
        byte[] content = "approved attachment".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(repository.resolve("docs"));
        Files.write(repository.resolve("docs/design.md"), content);
        AgentExecutionPlan plan = plan(repository, expectation(
                repository, "docs/design.md", content));

        RuntimeAttachmentVerifier verifier = new RuntimeAttachmentVerifier();

        assertDoesNotThrow(() -> verifier.verify(plan));
        assertFalse(verifier.toString().contains("approved attachment"));
        assertFalse(verifier.toString().contains(repository.toString()));
    }

    @Test
    void changedDeletedOrSymlinkedAttachmentShouldFailClosed() throws Exception {
        Path repository = Files.createDirectory(tempDir.resolve("repository-changed"));
        Files.createDirectories(repository.resolve("docs"));
        Path attachment = repository.resolve("docs/design.md");
        byte[] original = "approved".getBytes(StandardCharsets.UTF_8);
        Files.write(attachment, original);
        RuntimeAttachmentExpectation expectation = expectation(
                repository, "docs/design.md", original);
        RuntimeAttachmentVerifier verifier = new RuntimeAttachmentVerifier();

        Files.write(attachment, "changed!".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,
                () -> verifier.verify(plan(repository, expectation)));

        Files.delete(attachment);
        assertThrows(IllegalStateException.class,
                () -> verifier.verify(plan(repository, expectation)));

        Path outside = Files.write(tempDir.resolve("outside.md"), original);
        Files.createSymbolicLink(attachment, outside);
        assertThrows(IllegalStateException.class,
                () -> verifier.verify(plan(repository, expectation)));
    }

    @Test
    void replacementObservedAfterHashReadShouldFailBeforeLaunch() throws Exception {
        Path repository = Files.createDirectory(tempDir.resolve("repository-race"));
        Files.createDirectories(repository.resolve("docs"));
        Path attachment = repository.resolve("docs/design.md");
        byte[] original = "approved".getBytes(StandardCharsets.UTF_8);
        Files.write(attachment, original);
        RuntimeAttachmentVerifier verifier = new RuntimeAttachmentVerifier(
                path -> {
                    Files.delete(path);
                    Files.write(path, original);
                });

        assertThrows(IllegalStateException.class,
                () -> verifier.verify(plan(repository, expectation(
                        repository, "docs/design.md", original))));
    }

    @Test
    void uploadedAttachmentUsesOnlyIsolatedMaterializedDirectory()
            throws Exception {
        Path repository = Files.createDirectory(
                tempDir.resolve("repository-uploaded"));
        byte[] content = "browser upload".getBytes(StandardCharsets.UTF_8);
        String runtimeFileName = "attachment-1234567890abcdefabcd.md";
        RuntimeAttachmentExpectation expectation =
                RuntimeAttachmentExpectation.uploadedConversation(
                        "attachment-1",
                        CanonicalHashing.sha256("private-storage-key"),
                        runtimeFileName, CanonicalHashing.sha256(content),
                        content.length);
        AgentExecutionPlan plan = plan(repository, expectation);
        RuntimeWorkspaceMaterializer.MaterializedWorkspace workspace =
                new RuntimeWorkspaceMaterializer(
                        tempDir.resolve("runtime-uploaded-verifier"),
                        copyingStorage(content)).materialize(plan);

        RuntimeAttachmentVerifier verifier = new RuntimeAttachmentVerifier();

        assertDoesNotThrow(() -> verifier.verify(plan, workspace));
        Files.write(workspace.getAttachmentRoot().resolve(runtimeFileName),
                "tampered!!!!!".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,
                () -> verifier.verify(plan, workspace));
    }

    private AgentExecutionPlan plan(
            Path repository, RuntimeAttachmentExpectation expectation) {
        AgentExecutionPlan base = RuntimePlanFixtures.readOnly(
                "exec-attachment", repository,
                Collections.singletonList(repository),
                Collections.<String>emptySet(),
                com.example.agentweb.app.runtime.port.CredentialReference
                        .systemConfiguration());
        return new AgentExecutionPlan(
                base.getExecutionIdentity(), base.getRuntimeSelection(),
                base.getPromptPayload(), base.getWorkspaceLayout(),
                base.getCapabilityBinding(), base.getRuntimeLimits(),
                Collections.singletonList(expectation));
    }

    private RuntimeAttachmentExpectation expectation(
            Path repository, String relativePath, byte[] content) {
        return new RuntimeAttachmentExpectation(
                "repository", repository.toString(), relativePath,
                CanonicalHashing.sha256(content), content.length);
    }

    private UploadedConversationAttachmentStorage copyingStorage(
            byte[] content) {
        return new UploadedConversationAttachmentStorage() {
            @Override
            public StoredUploadedAttachment store(
                    UploadedAttachmentStorageRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void copyVerified(
                    String storageKey, Path destination,
                    String expectedSha256, long expectedSize) {
                try {
                    Files.write(destination, content);
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }

            @Override
            public void delete(String storageKey) {
                throw new UnsupportedOperationException();
            }
        };
    }

}
