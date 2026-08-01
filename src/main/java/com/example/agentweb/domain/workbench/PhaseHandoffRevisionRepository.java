package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * Phase Handoff append-only 历史修订仓储。
 *
 * <p>与只管理 latest 聚合生命周期的 {@link PhaseHandoffRepository} 分治。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public interface PhaseHandoffRevisionRepository {

    void append(PhaseHandoffRevision revision);

    Optional<PhaseHandoffRevision> findExact(
            WorkbenchId workbenchId, WorkbenchPhase sourcePhase,
            long version, String contentHash);
}
