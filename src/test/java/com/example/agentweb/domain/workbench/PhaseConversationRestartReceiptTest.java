package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase Conversation restart 幂等收据领域测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseConversationRestartReceiptTest {

    private static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");
    private static final WorkbenchPhase PHASE = WorkbenchPhase.SOLUTION_DESIGN;
    private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");

    @Test
    void sameOwnerKeyWorkbenchAndPhaseShouldReplayOriginalResult() {
        PhaseConversationRestartReceipt receipt = receipt();

        PhaseConversationRestartReceipt replayed = receipt.requireReplay(
                OWNER, "restart-key-1", WORKBENCH_ID, PHASE);

        assertEquals(receipt, replayed);
        assertEquals("session-0", replayed.getPreviousSessionId());
        assertEquals("session-1", replayed.getSessionId());
        assertEquals(1, replayed.getConversationGeneration());
        assertEquals(4L, replayed.getWorkbenchVersion());
    }

    @Test
    void sameKeyForDifferentRequestShouldFailWithIdempotencyConflict() {
        PhaseConversationRestartReceipt receipt = receipt();

        assertIdempotencyConflict(() -> receipt.requireReplay(
                OWNER, "restart-key-1", WorkbenchId.of("workbench-2"), PHASE));
        assertIdempotencyConflict(() -> receipt.requireReplay(
                OWNER, "restart-key-1", WORKBENCH_ID, WorkbenchPhase.IMPLEMENT_TEST));
    }

    @Test
    void foreignOwnerShouldFailWithOwnerRequired() {
        PhaseConversationRestartReceipt receipt = receipt();

        WorkbenchDomainException error = assertThrows(WorkbenchDomainException.class,
                () -> receipt.requireReplay(
                        OwnerReference.of("foreign", "Other"), "restart-key-1",
                        WORKBENCH_ID, PHASE));

        assertEquals(WorkbenchErrorCode.OWNER_REQUIRED, error.getCode());
    }

    @Test
    void persistedReceiptShouldRequireCompleteReferencedWorkbenchOwnerFacts() {
        PhaseConversationRestartReceipt receipt = receipt();

        receipt.requireWorkbenchOwner(OWNER);
        assertThrows(IllegalArgumentException.class, () -> receipt.requireWorkbenchOwner(
                OwnerReference.of("owner-1", "Renamed")));
        assertThrows(IllegalArgumentException.class, () -> receipt.requireWorkbenchOwner(
                OwnerReference.of("owner-2", "Alex")));
    }

    @Test
    void receiptShouldRejectInvalidBoundaryFacts() {
        assertThrows(IllegalArgumentException.class, () -> PhaseConversationRestartReceipt.record(
                OWNER, " ", WORKBENCH_ID, PHASE, "session-0", "session-1", 1, 4L, NOW));
        assertThrows(IllegalArgumentException.class, () -> PhaseConversationRestartReceipt.record(
                OWNER, "restart-key-1", WORKBENCH_ID, PHASE,
                "session-0", "session-1", -1, 4L, NOW));
        assertThrows(IllegalArgumentException.class, () -> PhaseConversationRestartReceipt.record(
                OWNER, "restart-key-1", WORKBENCH_ID, PHASE,
                "session-0", "session-1", 1, -1L, NOW));
    }

    private PhaseConversationRestartReceipt receipt() {
        return PhaseConversationRestartReceipt.record(
                OWNER, "restart-key-1", WORKBENCH_ID, PHASE,
                "session-0", "session-1", 1, 4L, NOW);
    }

    private void assertIdempotencyConflict(Runnable action) {
        WorkbenchDomainException error = assertThrows(WorkbenchDomainException.class, action::run);
        assertEquals(WorkbenchErrorCode.IDEMPOTENCY_CONFLICT, error.getCode());
    }
}
