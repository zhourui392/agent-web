package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.workbench.OwnerReference;

import java.util.Optional;

/**
 * 动态 Stage Conversation restart 幂等收据写侧 Repository。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface WorkbenchStageConversationRestartReceiptRepository {

    Optional<WorkbenchStageConversationRestartReceipt>
            findByOwnerAndIdempotencyKey(
                    OwnerReference owner, String idempotencyKey);

    void add(WorkbenchStageConversationRestartReceipt receipt);
}
