package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * 不可变人工 Review Opinion 版本的写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface ReviewOpinionRepository {

    void add(ReviewOpinion opinion);

    Optional<ReviewOpinion> find(WorkbenchId workbenchId, long version);

    Optional<ReviewOpinion> findLatest(WorkbenchId workbenchId);
}
