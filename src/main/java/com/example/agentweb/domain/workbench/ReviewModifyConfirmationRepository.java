package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * Review 修改运行的显式人工确认写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface ReviewModifyConfirmationRepository {

    void add(ReviewModifyConfirmation confirmation);

    Optional<ReviewModifyConfirmation> findById(String confirmationId);

    Optional<ReviewModifyConfirmation> findLatest(
            WorkbenchId workbenchId, long opinionVersion,
            String opinionHash);
}
