package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunAttachmentReference;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Workbench Run 提交命令的规范化输入与稳定幂等 Hash 契约。
 *
 * @author alex
 * @since 2026-08-01
 */
class SubmitWorkbenchRunCommandTest {

    private static final String HASH_A = repeat('a');
    private static final String HASH_B = repeat('b');

    @Test
    void commandShouldNormalizeBoundaryTextAndDefensivelyCopyAttachments() {
        List<WorkbenchRunAttachmentReference> source =
                new ArrayList<WorkbenchRunAttachmentReference>();
        source.add(attachment("agent-web", "src/Main.java", HASH_A));

        SubmitWorkbenchRunCommand command = command(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "  submit-key  ", "  Implement the change.  ",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L),
                "  review-7  ", source);
        source.add(attachment("service-api", "pom.xml", HASH_B));

        assertEquals("submit-key", command.getIdempotencyKey());
        assertEquals("Implement the change.", command.getMessage());
        assertEquals("review-7", command.getReviewConfirmationId());
        assertEquals(1, command.getAttachments().size());
        assertEquals(DocumentReference.of("agent-web", "src/Main.java"),
                command.getAttachments().get(0).getDocumentReference());
        assertEquals(HASH_A, command.getAttachments().get(0).getContentHash());
        assertThrows(UnsupportedOperationException.class,
                () -> command.getAttachments().clear());
    }

    @Test
    void requestHashShouldFollowCanonicalVersionedContract() {
        SubmitWorkbenchRunCommand command = command(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                Arrays.asList(
                        attachment("agent-web", "src/Main.java", HASH_A),
                        attachment("service-api", "pom.xml", HASH_B)));

        assertEquals("1982dae09f4824d4e8b5913594358e951df2e3ef25b8c4c2afc1d7fbac1b1089",
                command.getRequestHash());
    }

    @Test
    void idempotencyKeyAndExpectedVersionShouldNotChangeRequestHash() {
        SubmitWorkbenchRunCommand first = baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key-1", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                attachments());
        SubmitWorkbenchRunCommand retried = baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                99L, "submit-key-2", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                attachments());

        assertEquals(first.getRequestHash(), retried.getRequestHash());
    }

    @Test
    void everyAcceptedRequestFactShouldChangeRequestHash() {
        SubmitWorkbenchRunCommand base = baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                attachments());

        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-2"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                attachments()));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.REVIEW_REFACTOR,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                attachments()));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement another change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                attachments()));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.DISCUSS_READ_ONLY, Long.valueOf(4L), "review-7",
                attachments()));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, null, "review-7", attachments()));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(5L), "review-7",
                attachments()));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), null, attachments()));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-8",
                attachments()));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                Collections.<WorkbenchRunAttachmentReference>emptyList()));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                Arrays.asList(
                        attachment("service-api", "pom.xml", HASH_B),
                        attachment("agent-web", "src/Main.java", HASH_A))));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                Arrays.asList(
                        attachment("agent-web", "src/Other.java", HASH_A),
                        attachment("service-api", "pom.xml", HASH_B))));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                Arrays.asList(
                        attachment("agent-web", "src/Main.java", HASH_B),
                        attachment("service-api", "pom.xml", HASH_B))));
        assertHashDiffers(base, baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "Implement the change.",
                RunMode.MODIFY_WORKSPACE, Long.valueOf(4L), "review-7",
                Arrays.asList(
                        attachment("service-api", "src/Main.java", HASH_A),
                        attachment("service-api", "pom.xml", HASH_B))));
    }

    @Test
    void lengthFramingShouldDistinguishBoundaryCollisions() {
        SubmitWorkbenchRunCommand first = baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "a", RunMode.MODIFY_WORKSPACE,
                null, "bc", Collections.<WorkbenchRunAttachmentReference>emptyList());
        SubmitWorkbenchRunCommand second = baseCommand(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.IMPLEMENT_TEST,
                7L, "submit-key", "ab", RunMode.MODIFY_WORKSPACE,
                null, "c", Collections.<WorkbenchRunAttachmentReference>emptyList());

        assertNotEquals(first.getRequestHash(), second.getRequestHash());
    }

    @Test
    void commandAndAttachmentShouldRejectIncompleteOrUnsafeFacts() {
        assertThrows(IllegalArgumentException.class,
                () -> command(WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.IMPLEMENT_TEST, -1L, "key", "message",
                        RunMode.MODIFY_WORKSPACE, null, null,
                        Collections.<WorkbenchRunAttachmentReference>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> command(WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.IMPLEMENT_TEST, 0L, " ", "message",
                        RunMode.MODIFY_WORKSPACE, null, null,
                        Collections.<WorkbenchRunAttachmentReference>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> command(WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.IMPLEMENT_TEST, 0L, "key", " ",
                        RunMode.MODIFY_WORKSPACE, null, null,
                        Collections.<WorkbenchRunAttachmentReference>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> command(WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.IMPLEMENT_TEST, 0L, "key", "message",
                        RunMode.MODIFY_WORKSPACE, Long.valueOf(-1L), null,
                        Collections.<WorkbenchRunAttachmentReference>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> command(WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.IMPLEMENT_TEST, 0L, "key", "message",
                        RunMode.MODIFY_WORKSPACE, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> command(WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.IMPLEMENT_TEST, 0L, "key", "message",
                        RunMode.MODIFY_WORKSPACE, null, null,
                        Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class,
                () -> attachment("agent-web", "../secret", HASH_A));
        assertThrows(IllegalArgumentException.class,
                () -> attachment("agent-web", "src/Main.java", "not-a-sha256"));
    }

    @Test
    void commandShouldAllowEightAttachmentsAndRejectNinthOrDuplicateReference() {
        List<WorkbenchRunAttachmentReference> eight =
                attachmentReferences(8);

        SubmitWorkbenchRunCommand accepted = command(
                WorkbenchId.of("workbench-1"),
                WorkbenchPhase.IMPLEMENT_TEST, 0L, "key", "message",
                RunMode.MODIFY_WORKSPACE, null, null, eight);

        assertEquals(8, accepted.getAttachments().size());

        WorkbenchRunAttachmentReference first = eight.get(0);
        List<WorkbenchRunAttachmentReference> duplicate =
                new ArrayList<WorkbenchRunAttachmentReference>(eight);
        duplicate.set(1, WorkbenchRunAttachmentReference.of(
                first.getDocumentReference().getRepositoryKey(),
                first.getDocumentReference().getRelativePath(), HASH_B));

        assertThrows(IllegalStateException.class,
                () -> command(
                        WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.IMPLEMENT_TEST, 0L, "key", "message",
                        RunMode.MODIFY_WORKSPACE, null, null,
                        attachmentReferences(9)));
        assertThrows(IllegalStateException.class,
                () -> command(
                        WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.IMPLEMENT_TEST, 0L, "key", "message",
                        RunMode.MODIFY_WORKSPACE, null, null, duplicate));
    }

    private static SubmitWorkbenchRunCommand baseCommand(
            WorkbenchId workbenchId, WorkbenchPhase phase, long expectedVersion,
            String idempotencyKey, String message, RunMode runMode,
            Long handoffSourceVersion, String reviewConfirmationId,
            List<WorkbenchRunAttachmentReference> attachments) {
        return command(workbenchId, phase, expectedVersion, idempotencyKey,
                message, runMode, handoffSourceVersion, reviewConfirmationId,
                attachments);
    }

    private static SubmitWorkbenchRunCommand command(
            WorkbenchId workbenchId, WorkbenchPhase phase, long expectedVersion,
            String idempotencyKey, String message, RunMode runMode,
            Long handoffSourceVersion, String reviewConfirmationId,
            List<WorkbenchRunAttachmentReference> attachments) {
        return new SubmitWorkbenchRunCommand(
                workbenchId, phase, expectedVersion, idempotencyKey, message,
                runMode, handoffSourceVersion, reviewConfirmationId, attachments);
    }

    private static List<WorkbenchRunAttachmentReference> attachments() {
        return Arrays.asList(
                attachment("agent-web", "src/Main.java", HASH_A),
                attachment("service-api", "pom.xml", HASH_B));
    }

    private static WorkbenchRunAttachmentReference attachment(
            String repositoryKey, String relativePath, String contentHash) {
        return WorkbenchRunAttachmentReference.of(
                repositoryKey, relativePath, contentHash);
    }

    private static List<WorkbenchRunAttachmentReference> attachmentReferences(
            int count) {
        List<WorkbenchRunAttachmentReference> attachments =
                new ArrayList<WorkbenchRunAttachmentReference>(count);
        for (int index = 0; index < count; index++) {
            attachments.add(attachment(
                    "agent-web", "docs/attachment-" + index + ".md", HASH_A));
        }
        return attachments;
    }

    private static void assertHashDiffers(
            SubmitWorkbenchRunCommand first, SubmitWorkbenchRunCommand second) {
        assertNotEquals(first.getRequestHash(), second.getRequestHash());
    }

    private static String repeat(char character) {
        return String.join("", Collections.nCopies(64, String.valueOf(character)));
    }
}
