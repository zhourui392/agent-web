package com.example.agentweb.domain.mode;

import java.util.Optional;

/**
 * HandoffDocument 的写侧与幂等查询仓储。
 *
 * @author alex
 * @since 2026-08-20
 */
public interface HandoffDocumentRepository {

    HandoffDocument save(HandoffDocument document);

    Optional<HandoffDocument> find(String id);

    /** 幂等命中查询：(源会话, 幂等键) 唯一。 */
    Optional<HandoffDocument> findByFromSessionAndIdempotencyKey(
            String fromSessionId, String idempotencyKey);
}
