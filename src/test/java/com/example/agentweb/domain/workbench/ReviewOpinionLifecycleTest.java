package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Review Opinion 乐观版本与 exact Modify Confirmation 领域规则。
 *
 * @author alex
 * @since 2026-08-01
 */
class ReviewOpinionLifecycleTest {

    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final OwnerReference OTHER =
            OwnerReference.of("owner-2", "Other");
    private static final Instant NOW =
            Instant.parse("2026-08-01T15:00:00Z");
    private static final String CONTENT_A = "提取 Review 策略对象";
    private static final String CONTENT_B = "拆分 Review 策略并运行测试";
    private static final String HASH_A = CanonicalHashing.sha256(CONTENT_A);
    private static final String HASH_B = CanonicalHashing.sha256(CONTENT_B);

    @Test
    void initialOpinionShouldRequireExpectedZeroAndStartAtVersionOne() {
        ReviewOpinion opinion = ReviewOpinion.start(
                WORKBENCH_ID, 0L, "  " + CONTENT_A + "  ", OWNER, NOW);

        assertEquals(1L, opinion.getVersion());
        assertEquals(HASH_A, opinion.getContentHash());
        assertEquals(CONTENT_A, opinion.getContent());
        assertEquals(WorkbenchPhase.REVIEW_REFACTOR, opinion.getPhase());

        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class,
                () -> ReviewOpinion.start(
                        WORKBENCH_ID, 1L, CONTENT_A, OWNER, NOW));
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT,
                conflict.getCode());
        assertThrows(IllegalArgumentException.class,
                () -> ReviewOpinion.start(
                        WORKBENCH_ID, 0L,
                        new String(new char[16001]).replace('\0', 'x'),
                        OWNER, NOW));
    }

    @Test
    void reviseShouldRequireExactCurrentVersionAndKeepPriorVersionImmutable() {
        ReviewOpinion initial = ReviewOpinion.start(
                WORKBENCH_ID, 0L, CONTENT_A, OWNER, NOW);

        ReviewOpinion revised = initial.revise(
                1L, CONTENT_B, OWNER, NOW.plusSeconds(1));

        assertNotSame(initial, revised);
        assertEquals(1L, initial.getVersion());
        assertEquals(HASH_A, initial.getContentHash());
        assertEquals(2L, revised.getVersion());
        assertEquals(HASH_B, revised.getContentHash());

        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class,
                () -> revised.revise(
                        1L, CONTENT_A, OWNER, NOW.plusSeconds(2)));
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT,
                conflict.getCode());
    }

    @Test
    void confirmationShouldBindExactOpinionVersionHashAndHumanActor() {
        ReviewOpinion opinion = ReviewOpinion.start(
                WORKBENCH_ID, 0L, CONTENT_A, OWNER, NOW);

        ReviewModifyConfirmation confirmation = opinion.confirmModify(
                "confirmation-1", 1L, HASH_A, OWNER,
                NOW.plusSeconds(1));

        assertEquals("confirmation-1", confirmation.getConfirmationId());
        assertEquals(1L, confirmation.getOpinionVersion());
        assertEquals(HASH_A, confirmation.getOpinionHash());
        assertEquals(OWNER, confirmation.getConfirmedBy());
        opinion.requireExactContent("  " + CONTENT_A + "  ");
        WorkbenchDomainException wrongMessage = assertThrows(
                WorkbenchDomainException.class,
                () -> opinion.requireExactContent(CONTENT_B));
        assertEquals(WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                wrongMessage.getCode());

        assertVersionConflict(() -> opinion.confirmModify(
                "confirmation-wrong-version", 2L, HASH_A,
                OWNER, NOW.plusSeconds(1)));
        assertVersionConflict(() -> opinion.confirmModify(
                "confirmation-wrong-hash", 1L, HASH_B,
                OWNER, NOW.plusSeconds(1)));

        WorkbenchDomainException foreignActor = assertThrows(
                WorkbenchDomainException.class,
                () -> opinion.confirmModify(
                        "confirmation-foreign", 1L, HASH_A,
                        OTHER, NOW.plusSeconds(1)));
        assertEquals(WorkbenchErrorCode.OWNER_REQUIRED,
                foreignActor.getCode());
    }

    private static void assertVersionConflict(Runnable action) {
        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class, action::run);
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT,
                conflict.getCode());
    }
}
