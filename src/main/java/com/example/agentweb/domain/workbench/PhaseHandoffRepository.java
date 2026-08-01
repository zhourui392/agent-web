package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * Phase Handoff 写侧 Repository，仅暴露聚合生命周期。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface PhaseHandoffRepository {

    Optional<PhaseHandoff> find(WorkbenchId workbenchId, WorkbenchPhase phase);

    void add(PhaseHandoff handoff);

    void update(PhaseHandoff handoff);
}
