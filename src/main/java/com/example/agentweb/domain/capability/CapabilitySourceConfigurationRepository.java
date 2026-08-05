package com.example.agentweb.domain.capability;

import java.util.Optional;

/**
 * Capability Source Configuration 写侧仓储。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface CapabilitySourceConfigurationRepository {

    Optional<CapabilitySourceConfiguration> find();

    void save(CapabilitySourceConfiguration configuration, long expectedVersion);
}
