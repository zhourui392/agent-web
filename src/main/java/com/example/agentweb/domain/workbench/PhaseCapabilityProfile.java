package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Workbench 单阶段默认能力请求和可覆盖边界的版本化领域值对象。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityProfile {

    public static final String HASH_SCHEMA = "workbench-phase-capability-profile@1";

    private final String profileId;
    private final String profileVersion;
    private final String profileHash;
    private final WorkbenchPhase phase;
    private final List<PhaseCapabilityReference> capabilities;
    private final PhaseCapabilityOverridePolicy overridePolicy;

    private PhaseCapabilityProfile(
            String profileId, String profileVersion, WorkbenchPhase phase,
            List<PhaseCapabilityReference> capabilities,
            String persistedHash, boolean verifyPersistedHash) {
        this.profileId = DomainText.require(
                profileId, "phase capability profile id", 160);
        this.profileVersion = DomainText.require(
                profileVersion, "phase capability profile version", 80);
        if (phase == null) {
            throw new IllegalArgumentException(
                    "phase capability profile phase must not be null");
        }
        this.phase = phase;
        this.capabilities = validatedCapabilities(capabilities);
        this.overridePolicy = deriveOverridePolicy();
        this.profileHash = computeProfileHash();
        if (verifyPersistedHash) {
            String restoredHash = DomainText.requireSha256(
                    persistedHash, "phase capability profile hash");
            if (!profileHash.equals(restoredHash)) {
                throw new IllegalArgumentException(
                        "phase capability profile hash does not match its facts");
            }
        }
    }

    public static PhaseCapabilityProfile create(
            String profileId, String profileVersion, WorkbenchPhase phase,
            List<PhaseCapabilityReference> capabilities) {
        return new PhaseCapabilityProfile(
                profileId, profileVersion, phase, capabilities, null, false);
    }

    public static PhaseCapabilityProfile restore(
            String profileId, String profileVersion, String profileHash,
            WorkbenchPhase phase,
            List<PhaseCapabilityReference> capabilities) {
        return new PhaseCapabilityProfile(
                profileId, profileVersion, phase, capabilities,
                profileHash, true);
    }

    public CapabilityOverride overrideWithSelectedOptionals(
            Set<String> selectedOptionalSkillIds,
            Set<String> selectedOptionalMcpIds,
            AdditionalCapabilityRule additionalRule) {
        if (selectedOptionalSkillIds == null
                || selectedOptionalSkillIds.contains(null)
                || selectedOptionalMcpIds == null
                || selectedOptionalMcpIds.contains(null)) {
            throw new IllegalArgumentException(
                    "selected optional capabilities must not be null or contain null");
        }
        Set<String> optionalSkills = optionalCapabilityIds(
                PhaseCapabilityType.SKILL);
        Set<String> optionalMcpServers = optionalCapabilityIds(
                PhaseCapabilityType.MCP_SERVER);
        if (!optionalSkills.containsAll(selectedOptionalSkillIds)
                || !optionalMcpServers.containsAll(selectedOptionalMcpIds)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "selected optional capability is outside the phase profile");
        }
        Set<String> removedOptionalSkills = new HashSet<String>(optionalSkills);
        removedOptionalSkills.removeAll(selectedOptionalSkillIds);
        return CapabilityOverride.withExplicitOptionalMcpSelection(
                Collections.<String>emptySet(), removedOptionalSkills,
                selectedOptionalMcpIds, Collections.<String>emptySet(),
                additionalRule);
    }

    private Set<String> optionalCapabilityIds(PhaseCapabilityType type) {
        Set<String> identifiers = new HashSet<String>();
        for (PhaseCapabilityReference capability : capabilities) {
            if (!capability.isRequired() && capability.getType() == type) {
                identifiers.add(capability.getId());
            }
        }
        return identifiers;
    }

    private List<PhaseCapabilityReference> validatedCapabilities(
            List<PhaseCapabilityReference> values) {
        if (values == null || values.isEmpty() || values.contains(null)) {
            throw new IllegalArgumentException(
                    "phase capability profile must contain capabilities without null");
        }
        List<PhaseCapabilityReference> ordered =
                new ArrayList<PhaseCapabilityReference>(values);
        Collections.sort(ordered,
                Comparator.comparing(PhaseCapabilityReference::getId));
        Set<String> identifiers = new HashSet<String>();
        for (PhaseCapabilityReference capability : ordered) {
            if (!identifiers.add(capability.getId())) {
                throw new IllegalArgumentException(
                        "phase capability ids must be globally unique: "
                                + capability.getId());
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    private PhaseCapabilityOverridePolicy deriveOverridePolicy() {
        Set<String> optionalSkills = new HashSet<String>();
        Set<String> optionalMcpServers = new HashSet<String>();
        Set<String> optionalRules = new HashSet<String>();
        Set<String> mandatoryCapabilities = new HashSet<String>();
        for (PhaseCapabilityReference capability : capabilities) {
            if (capability.isRequired()) {
                mandatoryCapabilities.add(capability.getId());
                continue;
            }
            switch (capability.getType()) {
                case SKILL:
                    optionalSkills.add(capability.getId());
                    break;
                case MCP_SERVER:
                    optionalMcpServers.add(capability.getId());
                    break;
                case RULE:
                    optionalRules.add(capability.getId());
                    break;
                default:
                    throw new IllegalStateException(
                            "unsupported phase capability type");
            }
        }
        return PhaseCapabilityOverridePolicy.constrainedTo(
                phase, optionalSkills, optionalMcpServers,
                optionalRules, mandatoryCapabilities);
    }

    private String computeProfileHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", HASH_SCHEMA);
        CanonicalHashing.appendFramed(canonical, "profileId", profileId);
        CanonicalHashing.appendFramed(canonical, "profileVersion", profileVersion);
        CanonicalHashing.appendFramed(canonical, "phase", phase);
        CanonicalHashing.appendFramed(
                canonical, "capabilityCount", capabilities.size());
        for (PhaseCapabilityReference capability : capabilities) {
            CanonicalHashing.appendFramed(
                    canonical, "capabilityId", capability.getId());
            CanonicalHashing.appendFramed(
                    canonical, "capabilityType", capability.getType());
            CanonicalHashing.appendFramed(
                    canonical, "capabilityRequired", capability.isRequired());
        }
        return CanonicalHashing.sha256(canonical.toString());
    }
}
