package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dynamic Stage 上传附件的身份、代际和生命周期测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchStageUploadedConversationAttachmentTest {

    private static final Instant NOW = Instant.parse(
            "2026-08-05T08:00:00Z");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-stage-upload");
    private static final String CONTENT_HASH = repeat('a');
    private static final UploadedAttachmentPolicy POLICY =
            UploadedAttachmentPolicy.standard(
                    10L * 1024L * 1024L, 16,
                    Duration.ofHours(24), Duration.ofHours(2));

    @Test
    void should_VerifyOnlyExactStageConversationGenerationAndContent() {
        // Given
        WorkbenchStageUploadedConversationAttachment attachment = upload();

        // When
        VerifiedWorkbenchStageUploadedConversationAttachment verified =
                attachment.verifyForRun(
                        binding(), CONTENT_HASH, NOW.plusSeconds(1));

        // Then
        assertEquals("stage-attachment-1", verified.getAttachmentId());
        assertEquals("stage-design",
                verified.getBinding().getStageInstanceIdentifier());
        assertEquals("design.md", verified.getDisplayName());
        assertTrue(verified.getRuntimeFileName().endsWith(".md"));
        assertFalse(verified.toString().contains(repeat('b')));

        assertUnavailable(() -> attachment.verifyForRun(
                new WorkbenchStageUploadedAttachmentBinding(
                        OWNER, WORKBENCH_ID, "stage-implementation",
                        "stage-session-1", 2),
                CONTENT_HASH, NOW.plusSeconds(1)));
        assertUnavailable(() -> attachment.verifyForRun(
                new WorkbenchStageUploadedAttachmentBinding(
                        OWNER, WORKBENCH_ID, "stage-design",
                        "stage-session-1", 3),
                CONTENT_HASH, NOW.plusSeconds(1)));
        assertUnavailable(() -> attachment.verifyForRun(
                binding(), repeat('c'), NOW.plusSeconds(1)));
    }

    @Test
    void should_BindExactPreparedFactsAndReleaseOnlyForTerminalRun() {
        // Given
        WorkbenchStageUploadedConversationAttachment attachment = upload();
        VerifiedWorkbenchStageUploadedConversationAttachment verified =
                attachment.verifyForRun(
                        binding(), CONTENT_HASH, NOW.plusSeconds(1));

        // When
        boolean bound = attachment.bindToRun(
                verified, "stage-run-1", NOW.plusSeconds(2), POLICY);

        // Then
        assertTrue(bound);
        assertFalse(attachment.bindToRun(
                verified, "stage-run-1", NOW.plusSeconds(3), POLICY));
        assertEquals(UploadedConversationAttachmentStatus.BOUND,
                attachment.getStatus());
        assertEquals("stage-run-1", attachment.getBoundRunId());
        assertUnavailable(() -> attachment.bindToRun(
                verified, "stage-run-2", NOW.plusSeconds(4), POLICY));

        assertTrue(attachment.releaseAfterTerminal(
                "stage-run-1", NOW.plusSeconds(5)));
        assertFalse(attachment.releaseAfterTerminal(
                "stage-run-1", NOW.plusSeconds(6)));
        assertEquals(UploadedConversationAttachmentStatus.RELEASE_PENDING,
                attachment.getStatus());
        assertTrue(attachment.requiresCleanupAt(NOW.plusSeconds(5)));
    }

    @Test
    void should_CancelOnlyAvailableAttachmentWithExactStageBinding() {
        // Given
        WorkbenchStageUploadedConversationAttachment attachment = upload();

        // When
        boolean cancelled = attachment.cancelAvailable(
                binding(), NOW.plusSeconds(1));

        // Then
        assertTrue(cancelled);
        assertEquals(UploadedConversationAttachmentStatus.RELEASE_PENDING,
                attachment.getStatus());
        assertUnavailable(() -> attachment.cancelAvailable(
                binding(), NOW.plusSeconds(2)));
    }

    @Test
    void should_EnforceCombinedDynamicStageAttachmentLimitAndIdentity() {
        // Given
        VerifiedWorkbenchStageUploadedConversationAttachment upload =
                upload().verifyForRun(
                        binding(), CONTENT_HASH, NOW.plusSeconds(1));

        // When / Then
        VerifiedWorkbenchStageRunAttachmentSet accepted =
                VerifiedWorkbenchStageRunAttachmentSet.of(
                        repositoryDocuments(7),
                        Collections.singletonList(upload));
        assertEquals(8, accepted.size());

        WorkbenchDomainException tooMany = assertThrows(
                WorkbenchDomainException.class,
                () -> VerifiedWorkbenchStageRunAttachmentSet.of(
                        repositoryDocuments(8),
                        Collections.singletonList(upload)));
        assertEquals(WorkbenchErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                tooMany.getCode());

        WorkbenchDomainException duplicate = assertThrows(
                WorkbenchDomainException.class,
                () -> VerifiedWorkbenchStageRunAttachmentSet.of(
                        Collections.emptyList(), List.of(upload, upload)));
        assertEquals(WorkbenchErrorCode.ATTACHMENT_INVALID,
                duplicate.getCode());
    }

    private WorkbenchStageUploadedConversationAttachment upload() {
        return WorkbenchStageUploadedConversationAttachment.upload(
                "stage-attachment-1", binding(), "design.md",
                "text/markdown", UploadedAttachmentContentSignature.TEXT,
                42L, CONTENT_HASH, repeat('b'), POLICY, NOW);
    }

    private WorkbenchStageUploadedAttachmentBinding binding() {
        return new WorkbenchStageUploadedAttachmentBinding(
                OWNER, WORKBENCH_ID, "stage-design",
                "stage-session-1", 2);
    }

    private List<VerifiedWorkbenchRunAttachment> repositoryDocuments(
            int count) {
        List<VerifiedWorkbenchRunAttachment> result =
                new ArrayList<VerifiedWorkbenchRunAttachment>();
        for (int index = 0; index < count; index++) {
            result.add(VerifiedWorkbenchRunAttachment.restore(
                    DocumentReference.of(
                            "agent-web", "docs/stage-" + index + ".md"),
                    repeat((char) ('c' + index % 4)),
                    "text/markdown", 10L + index));
        }
        return result;
    }

    private static void assertUnavailable(
            org.junit.jupiter.api.function.Executable action) {
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class, action);
        assertEquals(WorkbenchErrorCode.ATTACHMENT_UNAVAILABLE,
                failure.getCode());
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, String.valueOf(value)));
    }
}
