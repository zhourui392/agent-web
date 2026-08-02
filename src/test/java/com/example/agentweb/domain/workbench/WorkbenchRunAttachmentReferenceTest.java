package com.example.agentweb.domain.workbench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Run 类型化附件联合及统一选择不变量测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunAttachmentReferenceTest {

    @Test
    void resolvesEachUnionMemberWithoutApplicationTypeBranching() {
        WorkbenchRunAttachmentReference repository =
                WorkbenchRunAttachmentReference.repositoryDocument(
                        "agent-web", "docs/design.md", repeat('a'));
        WorkbenchRunAttachmentReference uploaded =
                WorkbenchRunAttachmentReference.uploadedConversation(
                        "attachment-1", repeat('b'));

        assertEquals("repository:agent-web/docs/design.md",
                repository.resolve(new RenderingResolver()));
        assertEquals("uploaded:attachment-1",
                uploaded.resolve(new RenderingResolver()));
    }

    @Test
    void selectionOwnsCombinedLimitUniquenessAndImmutability() {
        WorkbenchRunAttachmentReference repository =
                WorkbenchRunAttachmentReference.repositoryDocument(
                        "agent-web", "docs/design.md", repeat('a'));
        WorkbenchRunAttachmentReference sameIdentityDifferentHash =
                WorkbenchRunAttachmentReference.repositoryDocument(
                        "agent-web", "docs/design.md", repeat('b'));

        assertThrows(WorkbenchDomainException.class,
                () -> WorkbenchRunAttachmentSelection.immutable(
                        Arrays.asList(repository, sameIdentityDifferentHash)));
        assertThrows(WorkbenchDomainException.class,
                () -> WorkbenchRunAttachmentSelection.immutable(
                        Collections.nCopies(9, repository)));

        List<WorkbenchRunAttachmentReference> source =
                new ArrayList<WorkbenchRunAttachmentReference>();
        source.add(repository);
        List<WorkbenchRunAttachmentReference> selected =
                WorkbenchRunAttachmentSelection.immutable(source);
        source.clear();
        assertEquals(1, selected.size());
        assertThrows(UnsupportedOperationException.class, selected::clear);
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, Character.toString(value)));
    }

    private static final class RenderingResolver
            implements WorkbenchRunAttachmentReference.Resolver<String> {

        @Override
        public String repositoryDocument(
                DocumentReference documentReference, String contentHash) {
            return "repository:" + documentReference.getRepositoryKey()
                    + "/" + documentReference.getRelativePath();
        }

        @Override
        public String uploadedConversation(
                String attachmentId, String contentHash) {
            return "uploaded:" + attachmentId;
        }
    }
}
