package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 单次 Workbench Phase 能力解析的运行约束；Catalog 事实只能与这些约束求交。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityResolutionPolicy {

    private final String policyVersion;
    private final RunMode runMode;
    private final String runtime;
    private final String runtimeCompatibility;
    private final Set<SkillTrustSource> allowedSkillTrustSources;
    private final CapabilityAccess maximumMcpAccess;

    private PhaseCapabilityResolutionPolicy(
            String policyVersion, RunMode runMode,
            String runtime, String runtimeCompatibility,
            Set<SkillTrustSource> allowedSkillTrustSources) {
        this.policyVersion = DomainText.require(
                policyVersion, "workbench capability policy version", 120);
        if (runMode == null) {
            throw new IllegalArgumentException(
                    "workbench capability run mode must not be null");
        }
        this.runMode = runMode;
        this.runtime = DomainText.require(
                runtime, "workbench capability runtime", 120)
                .toUpperCase(Locale.ROOT);
        this.runtimeCompatibility = DomainText.require(
                runtimeCompatibility,
                "workbench runtime compatibility", 240);
        this.allowedSkillTrustSources = immutableTrustSources(
                allowedSkillTrustSources);
        this.maximumMcpAccess = runMode.modifiesWorkspace()
                ? CapabilityAccess.WRITE : CapabilityAccess.READ;
    }

    public static PhaseCapabilityResolutionPolicy forRun(
            String policyVersion, RunMode runMode,
            String runtime, String runtimeCompatibility,
            Set<SkillTrustSource> allowedSkillTrustSources) {
        return new PhaseCapabilityResolutionPolicy(
                policyVersion, runMode, runtime, runtimeCompatibility,
                allowedSkillTrustSources);
    }

    public static PhaseCapabilityResolutionPolicy forProfilePreview(
            String policyVersion, WorkbenchPhase phase,
            AgentType agentType) {
        if (phase == null || agentType == null) {
            throw new IllegalArgumentException(
                    "profile preview phase and agent type must not be null");
        }
        if (agentType == AgentType.NATIVE) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "NATIVE diagnosis runtime is unavailable to Workbench");
        }
        RunMode runMode = phase == WorkbenchPhase.IMPLEMENT_TEST
                ? RunMode.MODIFY_WORKSPACE
                : RunMode.DISCUSS_READ_ONLY;
        return new PhaseCapabilityResolutionPolicy(
                policyVersion, runMode, agentType.name(),
                agentType.name() + "_WORKBENCH@1",
                Collections.singleton(SkillTrustSource.PLATFORM));
    }

    public boolean allowsSkillTrustSource(SkillTrustSource trustSource) {
        return trustSource != null
                && allowedSkillTrustSources.contains(trustSource);
    }

    public boolean allowsMcpAccess(CapabilityAccess access) {
        if (access == CapabilityAccess.READ) {
            return true;
        }
        return access == CapabilityAccess.WRITE
                && maximumMcpAccess == CapabilityAccess.WRITE;
    }

    private static Set<SkillTrustSource> immutableTrustSources(
            Set<SkillTrustSource> values) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(
                    "allowed skill trust sources must not be null or contain null");
        }
        return Collections.unmodifiableSet(
                new HashSet<SkillTrustSource>(values));
    }
}
