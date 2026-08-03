package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileEntry;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 管理后台 Phase Capability Profile 视图。
 *
 * @author alex
 * @since 2026-08-02
 */
@Getter
public final class AdminPhaseCapabilityProfileView {

    private final String phase;
    private final String profileId;
    private final String profileVersion;
    private final String profileHash;
    private final List<CapabilityReferenceView> capabilities;
    private final String updatedById;
    private final String updatedByName;
    private final long updatedAt;
    private final long version;

    public AdminPhaseCapabilityProfileView(
            String phase, String profileId, String profileVersion,
            String profileHash, List<CapabilityReferenceView> capabilities,
            String updatedById, String updatedByName,
            long updatedAt, long version) {
        this.phase = phase;
        this.profileId = profileId;
        this.profileVersion = profileVersion;
        this.profileHash = profileHash;
        this.capabilities = Collections.unmodifiableList(capabilities);
        this.updatedById = updatedById;
        this.updatedByName = updatedByName;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static AdminPhaseCapabilityProfileView from(
            PhaseCapabilityProfileEntry entry) {
        List<CapabilityReferenceView> capabilities =
                new ArrayList<CapabilityReferenceView>();
        for (PhaseCapabilityReference reference
                : entry.getProfile().getCapabilities()) {
            capabilities.add(CapabilityReferenceView.from(reference));
        }
        OwnerReference updatedBy = entry.getUpdatedBy();
        return new AdminPhaseCapabilityProfileView(
                entry.getProfile().getPhase().name(),
                entry.getProfile().getProfileId(),
                entry.getProfile().getProfileVersion(),
                entry.getProfile().getProfileHash(),
                capabilities,
                updatedBy.getOwnerId(),
                updatedBy.getOwnerName(),
                entry.getUpdatedAt().toEpochMilli(),
                entry.getStorageVersion());
    }

    /**
     * 单项能力引用视图。
     */
    @Getter
    public static final class CapabilityReferenceView {
        private final String id;
        private final String type;
        private final boolean required;

        public CapabilityReferenceView(String id, String type, boolean required) {
            this.id = id;
            this.type = type;
            this.required = required;
        }

        public static CapabilityReferenceView from(
                PhaseCapabilityReference reference) {
            return new CapabilityReferenceView(
                    reference.getId(),
                    reference.getType().name(),
                    reference.isRequired());
        }
    }
}
