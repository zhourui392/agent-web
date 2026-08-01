package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * Workbench 创建幂等收据的写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchCreationRepository {

    Optional<WorkbenchCreationReceipt> findByOwnerAndIdempotencyKey(
            OwnerReference owner, String idempotencyKey);

    void add(WorkbenchCreationReceipt receipt);
}
