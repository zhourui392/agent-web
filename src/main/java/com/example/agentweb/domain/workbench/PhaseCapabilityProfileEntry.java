package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Phase Capability Profile 与管理元数据的版本化聚合。
 *
 * <p>包装不可变 {@link PhaseCapabilityProfile} 与管理员更新审计信息，
 * 供管理后台 CRUD 使用。{@code storageVersion} 用于乐观锁。</p>
 *
 * @author alex
 * @since 2026-08-02
 */
@Getter
public final class PhaseCapabilityProfileEntry {

    private final PhaseCapabilityProfile profile;
    private final OwnerReference updatedBy;
    private final Instant updatedAt;
    private final long storageVersion;

    private PhaseCapabilityProfileEntry(
            PhaseCapabilityProfile profile, OwnerReference updatedBy,
            Instant updatedAt, long storageVersion) {
        this.profile = Objects.requireNonNull(
                profile, "phase capability profile");
        this.updatedBy = Objects.requireNonNull(
                updatedBy, "profile updated by");
        this.updatedAt = DomainText.requireTime(
                updatedAt, "profile updated at");
        if (storageVersion < 1L) {
            throw new IllegalArgumentException(
                    "profile storage version must be positive");
        }
        this.storageVersion = storageVersion;
    }

    /**
     * 从持久化恢复。
     */
    public static PhaseCapabilityProfileEntry restore(
            PhaseCapabilityProfile profile, OwnerReference updatedBy,
            Instant updatedAt, long storageVersion) {
        return new PhaseCapabilityProfileEntry(
                profile, updatedBy, updatedAt, storageVersion);
    }

    /**
     * 管理员更新 capabilities，生成新版本 Profile。
     *
     * @param expectedStorageVersion 乐观锁期望值
     * @param capabilities            新的能力引用列表
     * @param actor                   操作者
     * @param now                     操作时间
     * @return 新 Entry（storageVersion + 1，profileVersion 自增）
     */
    public PhaseCapabilityProfileEntry updateCapabilities(
            long expectedStorageVersion,
            List<PhaseCapabilityReference> capabilities,
            OwnerReference actor, Instant now) {
        if (expectedStorageVersion != storageVersion) {
            throw PhaseCapabilityConfiguration.versionConflict();
        }
        if (capabilities == null || capabilities.isEmpty()) {
            throw new IllegalArgumentException(
                    "phase capability profile must contain at least one capability");
        }
        String nextProfileVersion = nextProfileVersion();
        List<PhaseCapabilityReference> immutable = Collections.unmodifiableList(
                new ArrayList<PhaseCapabilityReference>(capabilities));
        PhaseCapabilityProfile nextProfile = PhaseCapabilityProfile.create(
                profile.getProfileId(), nextProfileVersion,
                profile.getPhase(), immutable);
        Instant updateTime = DomainText.requireTime(
                now, "profile updated at");
        if (updateTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "profile update time must not move backwards");
        }
        return new PhaseCapabilityProfileEntry(
                nextProfile, actor, updateTime, storageVersion + 1L);
    }

    private String nextProfileVersion() {
        try {
            long current = Long.parseLong(profile.getProfileVersion());
            return String.valueOf(current + 1L);
        } catch (NumberFormatException ex) {
            return "1";
        }
    }
}
