package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * 高影响操作提案幂等收据的写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface HighImpactOperationProposalRepository {

    Optional<HighImpactOperationProposalReceipt> find(
            OwnerReference owner, WorkbenchId workbenchId,
            String idempotencyKey);

    void add(HighImpactOperationProposalReceipt receipt);
}
