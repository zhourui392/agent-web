package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * Phase Conversation restart 幂等收据写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface PhaseConversationRestartReceiptRepository {

    Optional<PhaseConversationRestartReceipt> findByOwnerAndIdempotencyKey(
            OwnerReference owner, String idempotencyKey);

    void add(PhaseConversationRestartReceipt receipt);
}
