package com.example.agentweb.domain.workbench;

import lombok.AccessLevel;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase Capability Override 的版本化资源槽位。
 *
 * <p>配置被删除后仍保留递增 token，避免 absent/version 0 混同和
 * delete/recreate ABA；创建、更新和 Profile rebase 均在领域内完成。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityConfigurationState {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private long version;
    @Getter(AccessLevel.NONE)
    private PhaseCapabilityConfiguration current;

    private PhaseCapabilityConfigurationState(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            long version, PhaseCapabilityConfiguration current) {
        this.workbenchId = Objects.requireNonNull(
                workbenchId, "workbenchId");
        this.phase = Objects.requireNonNull(phase, "phase");
        if (version < 0L) {
            throw new IllegalArgumentException(
                    "capability configuration state version must not be negative");
        }
        if (current != null
                && (!workbenchId.equals(current.getWorkbenchId())
                || phase != current.getPhase()
                || version != current.getVersion())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        this.version = version;
        this.current = current;
    }

    public static PhaseCapabilityConfigurationState initiallyAbsent(
            WorkbenchId workbenchId, WorkbenchPhase phase) {
        return new PhaseCapabilityConfigurationState(
                workbenchId, phase, 0L, null);
    }

    public static PhaseCapabilityConfigurationState absent(
            WorkbenchId workbenchId, WorkbenchPhase phase, long version) {
        if (version < 1L) {
            throw new IllegalArgumentException(
                    "deleted capability configuration version must be positive");
        }
        return new PhaseCapabilityConfigurationState(
                workbenchId, phase, version, null);
    }

    public static PhaseCapabilityConfigurationState present(
            PhaseCapabilityConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return new PhaseCapabilityConfigurationState(
                configuration.getWorkbenchId(), configuration.getPhase(),
                configuration.getVersion(), configuration);
    }

    public Optional<PhaseCapabilityConfiguration> getConfiguration() {
        return Optional.ofNullable(current);
    }

    public PhaseCapabilityConfiguration createOverride(
            PhaseCapabilityProfile profile, CapabilityOverride override,
            OwnerReference actor, Instant now) {
        if (version != 0L || current != null) {
            throw PhaseCapabilityConfiguration.versionConflict();
        }
        return replaceWithNewConfiguration(profile, override, actor, now);
    }

    public void requireCanCreate() {
        if (version != 0L || current != null) {
            throw PhaseCapabilityConfiguration.versionConflict();
        }
    }

    public void requireCanUpdate(long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        if (current == null) {
            throw PhaseCapabilityConfiguration.versionConflict();
        }
    }

    public void requireCanPut(long expectedVersion) {
        requireExpectedVersion(expectedVersion);
    }

    public PhaseCapabilityConfiguration updateOverride(
            long expectedVersion, PhaseCapabilityProfile profile,
            CapabilityOverride override, OwnerReference actor, Instant now) {
        requireExpectedVersion(expectedVersion);
        if (current == null) {
            throw PhaseCapabilityConfiguration.versionConflict();
        }
        current.changeOverride(
                expectedVersion, profile, override, actor, now);
        version = current.getVersion();
        return current;
    }

    public PhaseCapabilityConfiguration putOverride(
            long expectedVersion, PhaseCapabilityProfile profile,
            CapabilityOverride override, OwnerReference actor, Instant now) {
        requireExpectedVersion(expectedVersion);
        if (current == null) {
            return replaceWithNewConfiguration(profile, override, actor, now);
        }
        current.changeOverride(
                expectedVersion, profile, override, actor, now);
        version = current.getVersion();
        return current;
    }

    public PhaseCapabilityOverrideResolution resolveFor(
            PhaseCapabilityProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (profile.getPhase() != phase) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        return current == null
                ? profile.defaultOverrideResolution()
                : current.resolveFor(workbenchId, profile);
    }

    private PhaseCapabilityConfiguration replaceWithNewConfiguration(
            PhaseCapabilityProfile profile, CapabilityOverride override,
            OwnerReference actor, Instant now) {
        current = PhaseCapabilityConfiguration.createAfter(
                version, workbenchId, phase, profile, override, actor, now);
        version = current.getVersion();
        return current;
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion != version || version == Long.MAX_VALUE) {
            throw PhaseCapabilityConfiguration.versionConflict();
        }
    }
}
