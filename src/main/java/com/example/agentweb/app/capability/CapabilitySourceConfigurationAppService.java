package com.example.agentweb.app.capability;

import com.example.agentweb.app.capability.port.CapabilitySourceProbe;
import com.example.agentweb.domain.capability.CapabilityConfigurationEditor;
import com.example.agentweb.domain.capability.CapabilitySourceConfiguration;
import com.example.agentweb.domain.capability.CapabilitySourceConfigurationRepository;
import com.example.agentweb.domain.capability.CapabilitySourceVersionConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Capability Source 的查询、验证和原子更新用例。
 *
 * @author alex
 * @since 2026-08-05
 */
@Service
@Transactional(readOnly = true)
public class CapabilitySourceConfigurationAppService {

    private final CapabilitySourceConfigurationRepository repository;
    private final CapabilitySourceProbe probe;
    private final Clock clock;

    public CapabilitySourceConfigurationAppService(
            CapabilitySourceConfigurationRepository repository,
            CapabilitySourceProbe probe, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<CapabilitySourceConfiguration> find() {
        return repository.find();
    }

    public CapabilitySourceProbeResult validate(CapabilitySourceCandidate candidate) {
        return probe.probe(Objects.requireNonNull(candidate, "candidate"));
    }

    @Transactional
    public CapabilitySourceConfiguration update(
            CapabilitySourceCandidate candidate, long expectedVersion,
            CapabilityConfigurationEditor editor) {
        CapabilitySourceProbeResult result = validate(candidate);
        Optional<CapabilitySourceConfiguration> current = repository.find();
        CapabilitySourceConfiguration updated = current
                .map(configuration -> updateCurrent(
                        configuration, result, expectedVersion, editor))
                .orElseGet(() -> createFirst(result, expectedVersion, editor));
        repository.save(updated, expectedVersion);
        return updated;
    }

    private CapabilitySourceConfiguration updateCurrent(
            CapabilitySourceConfiguration current,
            CapabilitySourceProbeResult probed, long expectedVersion,
            CapabilityConfigurationEditor editor) {
        return current.update(expectedVersion,
                probed.getCommandCatalogDirectories(),
                probed.getSkillCatalogDirectories(),
                probed.getCanonicalMcpConfigurationJson(), editor, clock.instant());
    }

    private CapabilitySourceConfiguration createFirst(
            CapabilitySourceProbeResult probed, long expectedVersion,
            CapabilityConfigurationEditor editor) {
        if (expectedVersion != 0L) {
            throw new CapabilitySourceVersionConflictException(expectedVersion, 0L);
        }
        return CapabilitySourceConfiguration.create(
                probed.getCommandCatalogDirectories(),
                probed.getSkillCatalogDirectories(),
                probed.getCanonicalMcpConfigurationJson(), editor, clock.instant());
    }
}
