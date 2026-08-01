package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * Phase Capability Configuration 写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface PhaseCapabilityConfigurationRepository {

    Optional<PhaseCapabilityConfiguration> find(WorkbenchId workbenchId,
                                                WorkbenchPhase phase);

    PhaseCapabilityConfigurationState findState(
            WorkbenchId workbenchId, WorkbenchPhase phase);

    void save(PhaseCapabilityConfiguration configuration);

    long delete(WorkbenchId workbenchId, WorkbenchPhase phase,
                long expectedVersion);
}
