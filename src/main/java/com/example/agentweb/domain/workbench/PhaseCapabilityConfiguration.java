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
    private final String baseProfileId;
    private final String baseProfileVersion;
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
        if (version < 0L) {
            throw new IllegalArgumentException(
                    "capability configuration version must not be negative");
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
                override, updatedBy, updatedAt, 0L);
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
        if (expectedVersion != version) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "capability configuration expected version does not match");
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
        requirePolicy(policy, phase, nextOverride);
        override = nextOverride;
        updatedBy = actor;
        updatedAt = updateTime;
        version++;
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
}
