package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浏览器上传附件的媒体、不变量、代际绑定、过期和生命周期测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class UploadedConversationAttachmentTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-upload-1");
    private static final String HASH = repeat('a');
    private static final UploadedAttachmentPolicy POLICY =
            UploadedAttachmentPolicy.standard(
                    10L * 1024L * 1024L, 16,
                    Duration.ofHours(24), Duration.ofHours(2));

    @Test
    void trustedImageAndTextFactsShouldCreateAvailableAttachment() {
        UploadedAttachmentBinding binding = binding();

        UploadedConversationAttachment image = UploadedConversationAttachment.upload(
                "attachment-image", binding, "  architecture.png  ",
                "image/png", UploadedAttachmentContentSignature.PNG,
                128L, HASH, repeat('b'), POLICY, NOW);
        UploadedConversationAttachment source = UploadedConversationAttachment.upload(
                "attachment-source", binding, "Service.java",
                "application/octet-stream", UploadedAttachmentContentSignature.TEXT,
                256L, repeat('c'), repeat('d'), POLICY, NOW);

        assertEquals("architecture.png", image.getDisplayName());
        assertEquals("image/png", image.getMediaType());
        assertEquals(UploadedConversationAttachmentStatus.AVAILABLE,
                image.getStatus());
        assertEquals("text/x-java-source", source.getMediaType());
        assertEquals(NOW.plus(Duration.ofHours(24)), image.getExpiresAt());
        assertFalse(image.toString().contains(repeat('b')));
    }

    @Test
    void filenameMediaMagicEmptySizeAndExecutableContradictionsShouldFailClosed() {
        UploadedAttachmentBinding binding = binding();

        assertInvalid(() -> upload(binding, "../secret.txt", "text/plain",
                UploadedAttachmentContentSignature.TEXT, 10L));
        assertInvalid(() -> upload(binding, "bad\u0000.txt", "text/plain",
                UploadedAttachmentContentSignature.TEXT, 10L));
        assertInvalid(() -> upload(binding, "payload.exe", "application/octet-stream",
                UploadedAttachmentContentSignature.PE_EXECUTABLE, 10L));
        assertInvalid(() -> upload(binding, "photo.png", "image/jpeg",
                UploadedAttachmentContentSignature.PNG, 10L));
        assertInvalid(() -> upload(binding, "photo.png", "image/png",
                UploadedAttachmentContentSignature.JPEG, 10L));
        assertInvalid(() -> upload(binding, "empty.txt", "text/plain",
                UploadedAttachmentContentSignature.TEXT, 0L));
        assertInvalid(() -> upload(binding, "oversize.pdf", "application/pdf",
                UploadedAttachmentContentSignature.PDF,
                10L * 1024L * 1024L + 1L));
        assertInvalid(() -> upload(binding, "script.txt", "text/plain",
                UploadedAttachmentContentSignature.SHEBANG_EXECUTABLE, 20L));
    }

    @Test
    void exactOwnerWorkbenchPhaseGenerationAndHashShouldFreezeLogicalRunFacts() {
        UploadedConversationAttachment attachment = upload(
                binding(), "design.md", "text/markdown",
                UploadedAttachmentContentSignature.TEXT, 42L);

        VerifiedUploadedConversationAttachment verified =
                attachment.verifyForRun(binding(), HASH, NOW.plusSeconds(1));

        assertEquals("attachment-1", verified.getAttachmentId());
        assertEquals("design.md", verified.getDisplayName());
        assertEquals("text/markdown", verified.getMediaType());
        assertTrue(verified.getRuntimeFileName().endsWith(".md"));
        assertEquals("$AGENT_WORKBENCH_ATTACHMENT_DIR/"
                + verified.getRuntimeFileName(), verified.runtimeReference());
        assertFalse(verified.toString().contains(repeat('b')));

        assertUnavailable(() -> attachment.verifyForRun(
                new UploadedAttachmentBinding(
                        OWNER, WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                        "conversation-1", 3), HASH, NOW.plusSeconds(1)));
        assertUnavailable(() -> attachment.verifyForRun(
                new UploadedAttachmentBinding(
                        OWNER, WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "conversation-2", 4), HASH, NOW.plusSeconds(1)));
        assertUnavailable(() -> attachment.verifyForRun(
                binding(), repeat('e'), NOW.plusSeconds(1)));
    }

    @Test
    void boundAttachmentShouldPermitExactIdempotentBindThenReleaseForCleanup() {
        UploadedConversationAttachment attachment = upload(
                binding(), "design.md", "text/markdown",
                UploadedAttachmentContentSignature.TEXT, 42L);
        VerifiedUploadedConversationAttachment verified =
                attachment.verifyForRun(binding(), HASH, NOW.plusSeconds(1));

        assertTrue(attachment.bindToRun(
                verified, "run-1", NOW.plusSeconds(2), POLICY));
        assertFalse(attachment.bindToRun(
                verified, "run-1", NOW.plusSeconds(3), POLICY));
        assertEquals(UploadedConversationAttachmentStatus.BOUND,
                attachment.getStatus());
        assertEquals("run-1", attachment.getBoundRunId());
        assertEquals(NOW.plusSeconds(2).plus(Duration.ofHours(2)),
                attachment.getExpiresAt());

        assertUnavailable(() -> attachment.bindToRun(
                verified, "run-2", NOW.plusSeconds(4), POLICY));
        assertTrue(attachment.releaseAfterTerminal(
                "run-1", NOW.plusSeconds(5)));
        assertFalse(attachment.releaseAfterTerminal(
                "run-1", NOW.plusSeconds(6)));
        assertEquals(UploadedConversationAttachmentStatus.RELEASE_PENDING,
                attachment.getStatus());
        assertTrue(attachment.requiresCleanupAt(NOW.plusSeconds(5)));
    }

    @Test
    void expiryAndCombinedRepositoryUploadQuotaShouldBeDomainRules() {
        UploadedConversationAttachment attachment = upload(
                binding(), "design.md", "text/markdown",
                UploadedAttachmentContentSignature.TEXT, 42L);

        assertUnavailable(() -> attachment.verifyForRun(
                binding(), HASH, NOW.plus(Duration.ofHours(24))));
        assertDoesNotThrow(() -> POLICY.requireAvailableQuota(15L));
        WorkbenchDomainException quota = assertThrows(
                WorkbenchDomainException.class,
                () -> POLICY.requireAvailableQuota(16L));
        assertEquals(WorkbenchErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                quota.getCode());

        java.util.List<VerifiedWorkbenchRunAttachment> sevenRepositories =
                repositories(7);
        java.util.List<VerifiedWorkbenchRunAttachment> eightRepositories =
                repositories(8);
        VerifiedUploadedConversationAttachment upload = attachment.verifyForRun(
                binding(), HASH, NOW.plusSeconds(1));
        assertDoesNotThrow(() -> VerifiedWorkbenchRunAttachmentSet.of(
                sevenRepositories,
                Collections.singletonList(upload)));
        WorkbenchDomainException tooMany = assertThrows(
                WorkbenchDomainException.class,
                () -> VerifiedWorkbenchRunAttachmentSet.of(
                        eightRepositories,
                        Collections.singletonList(upload)));
        assertEquals(WorkbenchErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                tooMany.getCode());
    }

    @Test
    void onlyExactAvailableAttachmentCanBeCancelledForCleanup() {
        UploadedConversationAttachment attachment = upload(
                binding(), "design.md", "text/markdown",
                UploadedAttachmentContentSignature.TEXT, 42L);

        assertTrue(attachment.cancelAvailable(
                binding(), NOW.plusSeconds(1)));
        assertEquals(UploadedConversationAttachmentStatus.RELEASE_PENDING,
                attachment.getStatus());
        assertEquals(null, attachment.getBoundRunId());
        assertTrue(attachment.requiresCleanupAt(NOW.plusSeconds(1)));
        assertUnavailable(() -> attachment.cancelAvailable(
                binding(), NOW.plusSeconds(2)));

        UploadedConversationAttachment foreign = upload(
                binding(), "foreign.md", "text/markdown",
                UploadedAttachmentContentSignature.TEXT, 42L);
        assertUnavailable(() -> foreign.cancelAvailable(
                new UploadedAttachmentBinding(
                        OwnerReference.of("other", "Other"), WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "conversation-1", 3), NOW.plusSeconds(1)));
    }

    private UploadedConversationAttachment upload(
            UploadedAttachmentBinding binding, String displayName,
            String clientMediaType, UploadedAttachmentContentSignature signature,
            long size) {
        return UploadedConversationAttachment.upload(
                "attachment-1", binding, displayName, clientMediaType,
                signature, size, HASH, repeat('b'), POLICY, NOW);
    }

    private UploadedAttachmentBinding binding() {
        return new UploadedAttachmentBinding(
                OWNER, WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "conversation-1", 3);
    }

    private java.util.List<VerifiedWorkbenchRunAttachment> repositories(
            int count) {
        java.util.List<VerifiedWorkbenchRunAttachment> result =
                new ArrayList<VerifiedWorkbenchRunAttachment>();
        for (int index = 0; index < count; index++) {
            result.add(VerifiedWorkbenchRunAttachment.restore(
                    DocumentReference.of(
                            "agent-web", "docs/reference-" + index + ".md"),
                    repeat((char) ('f' - index % 5)),
                    "text/markdown", 30L + index));
        }
        return result;
    }

    private static void assertInvalid(Executable executable) {
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class, executable);
        assertTrue(Arrays.asList(
                        WorkbenchErrorCode.ATTACHMENT_INVALID,
                        WorkbenchErrorCode.ATTACHMENT_TOO_LARGE)
                .contains(failure.getCode()));
    }

    private static void assertUnavailable(Executable executable) {
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class, executable);
        assertEquals(WorkbenchErrorCode.ATTACHMENT_UNAVAILABLE,
                failure.getCode());
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, String.valueOf(value)));
    }
}
