package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Workbench Run 附件从客户端声明升级为可信事实的领域规则测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class VerifiedWorkbenchRunAttachmentTest {

    private static final String HASH_A = repeat('a');
    private static final String HASH_B = repeat('b');

    @Test
    void exactObservedFactsShouldProduceImmutableVerifiedAttachment() {
        DocumentReference reference = DocumentReference.of(
                "agent-web", "docs/design.md");

        VerifiedWorkbenchRunAttachment attachment =
                VerifiedWorkbenchRunAttachment.verify(
                        reference, HASH_A, reference, HASH_A,
                        "text/markdown", 42L, false);

        assertEquals(reference, attachment.getDocumentReference());
        assertEquals(HASH_A, attachment.getContentVersion());
        assertEquals("text/markdown", attachment.getMediaType());
        assertEquals(42L, attachment.getSize());
    }

    @Test
    void observedReferenceMismatchShouldRejectCorruptedBinding() {
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> VerifiedWorkbenchRunAttachment.verify(
                        DocumentReference.of("agent-web", "docs/design.md"),
                        HASH_A,
                        DocumentReference.of("agent-web", "docs/other.md"),
                        HASH_A, "text/markdown", 42L, false));

        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                failure.getCode());
    }

    @Test
    void changedOrDeletedContentShouldRejectStaleAttachment() {
        DocumentReference reference = DocumentReference.of(
                "agent-web", "docs/design.md");

        WorkbenchDomainException changed = assertThrows(
                WorkbenchDomainException.class,
                () -> VerifiedWorkbenchRunAttachment.verify(
                        reference, HASH_A, reference, HASH_B,
                        "text/markdown", 42L, false));
        WorkbenchDomainException deleted = assertThrows(
                WorkbenchDomainException.class,
                () -> VerifiedWorkbenchRunAttachment.verify(
                        reference, HASH_A, reference, HASH_A,
                        "text/markdown", 42L, true));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT,
                changed.getCode());
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT,
                deleted.getCode());
    }

    @Test
    void requestReferencesShouldAllowEightAndRejectNinthOrDuplicate() {
        List<DocumentReference> eight = references(8);

        assertDoesNotThrow(() ->
                VerifiedWorkbenchRunAttachment.requireValidRequestReferences(
                        eight));

        List<DocumentReference> nine = references(9);
        WorkbenchDomainException tooMany = assertThrows(
                WorkbenchDomainException.class,
                () -> VerifiedWorkbenchRunAttachment
                        .requireValidRequestReferences(nine));
        WorkbenchDomainException duplicate = assertThrows(
                WorkbenchDomainException.class,
                () -> VerifiedWorkbenchRunAttachment
                        .requireValidRequestReferences(
                                java.util.Arrays.asList(
                                        eight.get(0), eight.get(0))));

        assertEquals(WorkbenchErrorCode.REQUEST_INVALID,
                tooMany.getCode());
        assertEquals(WorkbenchErrorCode.REQUEST_INVALID,
                duplicate.getCode());
    }

    @Test
    void verifiedListShouldDefensivelyCopyAndRemainImmutable() {
        DocumentReference firstReference = DocumentReference.of(
                "agent-web", "docs/first.md");
        DocumentReference secondReference = DocumentReference.of(
                "agent-web", "docs/second.md");
        VerifiedWorkbenchRunAttachment first =
                VerifiedWorkbenchRunAttachment.verify(
                        firstReference, HASH_A,
                        firstReference, HASH_A,
                        "text/markdown", 42L, false);
        VerifiedWorkbenchRunAttachment second =
                VerifiedWorkbenchRunAttachment.verify(
                        secondReference, HASH_B,
                        secondReference, HASH_B,
                        "text/markdown", 43L, false);
        List<VerifiedWorkbenchRunAttachment> source =
                new ArrayList<VerifiedWorkbenchRunAttachment>();
        source.add(first);

        List<VerifiedWorkbenchRunAttachment> immutable =
                VerifiedWorkbenchRunAttachment.immutableList(source);
        source.add(second);

        assertEquals(Collections.singletonList(first), immutable);
        assertThrows(UnsupportedOperationException.class,
                () -> immutable.add(second));
    }

    @Test
    void verificationShouldRejectIncompleteOrMalformedObservedFacts() {
        DocumentReference reference = DocumentReference.of(
                "agent-web", "docs/design.md");

        assertThrows(IllegalArgumentException.class,
                () -> VerifiedWorkbenchRunAttachment.verify(
                        null, HASH_A, reference, HASH_A,
                        "text/markdown", 42L, false));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedWorkbenchRunAttachment.verify(
                        reference, "invalid", reference, HASH_A,
                        "text/markdown", 42L, false));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedWorkbenchRunAttachment.verify(
                        reference, HASH_A, null, HASH_A,
                        "text/markdown", 42L, false));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedWorkbenchRunAttachment.verify(
                        reference, HASH_A, reference, "invalid",
                        "text/markdown", 42L, false));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedWorkbenchRunAttachment.verify(
                        reference, HASH_A, reference, HASH_A,
                        " ", 42L, false));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedWorkbenchRunAttachment.verify(
                        reference, HASH_A, reference, HASH_A,
                        repeatText('m', 161), 42L, false));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedWorkbenchRunAttachment.verify(
                        reference, HASH_A, reference, HASH_A,
                        "text/markdown", -1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedWorkbenchRunAttachment.verify(
                        reference, HASH_A, reference, HASH_A,
                        "text/markdown", (long) Integer.MAX_VALUE + 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedWorkbenchRunAttachment
                        .requireValidRequestReferences(null));
        assertThrows(IllegalArgumentException.class,
                () -> VerifiedWorkbenchRunAttachment
                        .requireValidRequestReferences(
                                Collections.singletonList(null)));
    }

    private static List<DocumentReference> references(int count) {
        List<DocumentReference> references =
                new ArrayList<DocumentReference>(count);
        for (int index = 0; index < count; index++) {
            references.add(DocumentReference.of(
                    "agent-web", "docs/attachment-" + index + ".md"));
        }
        return references;
    }

    private static String repeat(char value) {
        return String.join(
                "", Collections.nCopies(64, String.valueOf(value)));
    }

    private static String repeatText(char value, int count) {
        return String.join(
                "", Collections.nCopies(count, String.valueOf(value)));
    }
}
