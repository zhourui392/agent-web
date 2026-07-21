package com.example.agentweb.app.delivery;

import com.example.agentweb.domain.delivery.MergeRequestRef;

import java.util.List;

/**
 * MR 引用落库(merge_request_ref 表)。接口放 app、infra 实现,对齐 PortLeaseStore 模式守 ArchUnit A4;
 * UNIQUE(requirement_id, mr_iid) 由 upsert 语义消化 webhook 重放。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface MergeRequestStore {

    void upsert(String requirementId, MergeRequestRef ref);

    List<MergeRequestRef> findByRequirementId(String requirementId);
}
