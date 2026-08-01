package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;

/**
 * Workbench 单阶段高级能力覆盖聚合；历史 Run Snapshot 不随配置更新。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityConfiguration {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private String baseProfileId;
    private String baseProfileVersion;
    private CapabilityOverride override;
    private OwnerReference updatedBy;
    private Instant updatedAt;
    private long version;

    private PhaseCapabilityConfiguration(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            String baseProfileId, String baseProfileVersion,
            CapabilityOverride override, OwnerReference updatedBy,
            Instant updatedAt, long version) {
        if (workbenchId == null || phase == null || override == null || updatedBy == null) {
            throw new IllegalArgumentException(
                    "capability configuration required values must not be null");
        }
        if (version < 1L) {
            throw new IllegalArgumentException(
                    "persisted capability configuration version must be positive");
        }
        this.workbenchId = workbenchId;
        this.phase = phase;
        this.baseProfileId = DomainText.require(
                baseProfileId, "capability base profile id", 160);
        this.baseProfileVersion = DomainText.require(
                baseProfileVersion, "capability base profile version", 80);
        this.override = override;
        this.updatedBy = updatedBy;
        this.updatedAt = DomainText.requireTime(
                updatedAt, "capability configuration updated at");
        this.version = version;
    }

    public static PhaseCapabilityConfiguration create(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            String baseProfileId, String baseProfileVersion,
            CapabilityOverride override, PhaseCapabilityOverridePolicy policy,
            OwnerReference updatedBy, Instant updatedAt) {
        requirePolicy(policy, phase, override);
        return new PhaseCapabilityConfiguration(
                workbenchId, phase, baseProfileId, baseProfileVersion,
                override, updatedBy, updatedAt, 1L);
    }

    static PhaseCapabilityConfiguration createAfter(
            long previousVersion, WorkbenchId workbenchId,
            WorkbenchPhase phase, PhaseCapabilityProfile profile,
            CapabilityOverride override, OwnerReference updatedBy,
            Instant updatedAt) {
        if (previousVersion < 0L) {
            throw new IllegalArgumentException(
                    "previous capability configuration version must not be negative");
        }
        long nextVersion;
        try {
            nextVersion = Math.addExact(previousVersion, 1L);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "capability configuration version is exhausted", failure);
        }
        requireProfile(profile, phase, override);
        return new PhaseCapabilityConfiguration(
                workbenchId, phase, profile.getProfileId(),
                profile.getProfileVersion(), override, updatedBy,
                updatedAt, nextVersion);
    }

    public static PhaseCapabilityConfiguration restore(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            String baseProfileId, String baseProfileVersion,
            CapabilityOverride override, OwnerReference updatedBy,
            Instant updatedAt, long version,
            PhaseCapabilityOverridePolicy policy) {
        requirePolicy(policy, phase, override);
        return new PhaseCapabilityConfiguration(
                workbenchId, phase, baseProfileId, baseProfileVersion,
                override, updatedBy, updatedAt, version);
    }

    public void changeOverride(
            long expectedVersion, CapabilityOverride nextOverride,
            PhaseCapabilityOverridePolicy policy, OwnerReference actor, Instant now) {
        Instant updateTime = requireChange(
                expectedVersion, actor, now);
        requirePolicy(policy, phase, nextOverride);
        override = nextOverride;
        updatedBy = actor;
        updatedAt = updateTime;
        version++;
    }

    public void changeOverride(
            long expectedVersion, PhaseCapabilityProfile currentProfile,
            CapabilityOverride nextOverride, OwnerReference actor,
            Instant now) {
        Instant updateTime = requireChange(expectedVersion, actor, now);
        requireProfile(currentProfile, phase, nextOverride);
        baseProfileId = currentProfile.getProfileId();
        baseProfileVersion = currentProfile.getProfileVersion();
        override = nextOverride;
        updatedBy = actor;
        updatedAt = updateTime;
        version++;
    }

    public PhaseCapabilityOverrideResolution resolveFor(
            WorkbenchId expectedWorkbenchId,
            PhaseCapabilityProfile currentProfile) {
        if (expectedWorkbenchId == null || currentProfile == null) {
            throw new IllegalArgumentException(
                    "capability override resolution target must be complete");
        }
        if (!workbenchId.equals(expectedWorkbenchId)
                || phase != currentProfile.getPhase()) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        if (!baseProfileId.equals(currentProfile.getProfileId())
                || !baseProfileVersion.equals(
                currentProfile.getProfileVersion())) {
            return PhaseCapabilityOverrideResolution.restoredDefault(
                    baseProfileId, baseProfileVersion);
        }
        return currentProfile.resolveOverride(override);
    }

    private static void requirePolicy(
            PhaseCapabilityOverridePolicy policy, WorkbenchPhase phase,
            CapabilityOverride override) {
        if (policy == null) {
            throw new IllegalArgumentException(
                    "capability override policy must not be null");
        }
        policy.requireAllowed(phase, override);
    }

    private static void requireProfile(
            PhaseCapabilityProfile profile, WorkbenchPhase phase,
            CapabilityOverride override) {
        if (profile == null) {
            throw new IllegalArgumentException(
                    "capability profile must not be null");
        }
        if (profile.getPhase() != phase) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        requirePolicy(profile.getOverridePolicy(), phase, override);
    }

    private Instant requireChange(
            long expectedVersion, OwnerReference actor, Instant now) {
        if (expectedVersion != version) {
            throw versionConflict();
        }
        if (actor == null) {
            throw new IllegalArgumentException(
                    "capability configuration updater must not be null");
        }
        Instant updateTime = DomainText.requireTime(
                now, "capability configuration updated at");
        if (updateTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "capability configuration time must not move backwards");
        }
        if (version == Long.MAX_VALUE) {
            throw versionConflict();
        }
        return updateTime;
    }

    static WorkbenchDomainException versionConflict() {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.VERSION_CONFLICT,
                "capability configuration expected version does not match");
    }
}
