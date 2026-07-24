package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * Runtime Preflight 后可由目标 Runtime 真实强制的能力边界。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class RuntimeEnforcementProfile {

    private final String profileVersion;
    private final String adapterVersion;
    private final String runtimeVersion;
    private final String compatibilityMatrixVersion;
    private final String sandboxMode;
    private final boolean singleRunOverridesEnforced;
    private final boolean toolAllowDenyEnforced;
    private final boolean userConfigIsolated;
    private final boolean projectConfigAbsent;
    private final boolean repoSkillIsolationEnforced;
    private final boolean processTreeCancellationEnforced;
    private final String profileHash;

    public RuntimeEnforcementProfile(String profileVersion, String adapterVersion,
                                     String runtimeVersion, String compatibilityMatrixVersion,
                                     String sandboxMode, boolean singleRunOverridesEnforced,
                                     boolean toolAllowDenyEnforced, boolean userConfigIsolated,
                                     boolean projectConfigAbsent, boolean repoSkillIsolationEnforced,
                                     boolean processTreeCancellationEnforced) {
        this(profileVersion, adapterVersion, runtimeVersion, compatibilityMatrixVersion,
                sandboxMode, singleRunOverridesEnforced, toolAllowDenyEnforced,
                userConfigIsolated, projectConfigAbsent, repoSkillIsolationEnforced,
                processTreeCancellationEnforced, false);
    }

    private RuntimeEnforcementProfile(String profileVersion, String adapterVersion,
                                      String runtimeVersion, String compatibilityMatrixVersion,
                                      String sandboxMode, boolean singleRunOverridesEnforced,
                                      boolean toolAllowDenyEnforced, boolean userConfigIsolated,
                                      boolean projectConfigAbsent, boolean repoSkillIsolationEnforced,
                                      boolean processTreeCancellationEnforced,
                                      boolean legacyHash) {
        this.profileVersion = DomainText.require(profileVersion, "enforcement profile version", 120);
        this.adapterVersion = DomainText.require(adapterVersion, "runtime adapter version", 160);
        this.runtimeVersion = DomainText.require(runtimeVersion, "runtime version", 160);
        this.compatibilityMatrixVersion = DomainText.require(compatibilityMatrixVersion,
                "runtime compatibility matrix version", 160);
        this.sandboxMode = DomainText.require(sandboxMode, "runtime sandbox mode", 120);
        this.singleRunOverridesEnforced = singleRunOverridesEnforced;
        this.toolAllowDenyEnforced = toolAllowDenyEnforced;
        this.userConfigIsolated = userConfigIsolated;
        this.projectConfigAbsent = projectConfigAbsent;
        this.repoSkillIsolationEnforced = repoSkillIsolationEnforced;
        this.processTreeCancellationEnforced = processTreeCancellationEnforced;
        this.profileHash = legacyHash ? legacyHash() : currentHash();
    }

    private String currentHash() {
        StringBuilder canonical = new StringBuilder();
        HarnessHashing.appendFramed(canonical, "profileVersion", this.profileVersion);
        HarnessHashing.appendFramed(canonical, "adapterVersion", this.adapterVersion);
        HarnessHashing.appendFramed(canonical, "runtimeVersion", this.runtimeVersion);
        HarnessHashing.appendFramed(canonical, "compatibilityMatrixVersion",
                this.compatibilityMatrixVersion);
        HarnessHashing.appendFramed(canonical, "sandboxMode", this.sandboxMode);
        HarnessHashing.appendFramed(canonical, "singleRunOverrides",
                singleRunOverridesEnforced);
        HarnessHashing.appendFramed(canonical, "toolAllowDeny", toolAllowDenyEnforced);
        HarnessHashing.appendFramed(canonical, "userConfigIsolated", userConfigIsolated);
        HarnessHashing.appendFramed(canonical, "projectConfigAbsent", projectConfigAbsent);
        HarnessHashing.appendFramed(canonical, "repoSkillIsolation",
                repoSkillIsolationEnforced);
        HarnessHashing.appendFramed(canonical, "processTreeCancellation",
                processTreeCancellationEnforced);
        return HarnessHashing.sha256(canonical.toString());
    }

    private String legacyHash() {
        StringBuilder canonical = new StringBuilder();
        HarnessHashing.appendFramed(canonical, "profileVersion", profileVersion);
        HarnessHashing.appendFramed(canonical, "runtimeVersion", runtimeVersion);
        HarnessHashing.appendFramed(canonical, "sandboxMode", sandboxMode);
        HarnessHashing.appendFramed(canonical, "toolAllowlist", toolAllowDenyEnforced);
        HarnessHashing.appendFramed(canonical, "userConfigIsolated", userConfigIsolated);
        HarnessHashing.appendFramed(canonical, "cancellation",
                processTreeCancellationEnforced);
        return HarnessHashing.sha256(canonical.toString());
    }

    public RuntimeEnforcementProfile(String profileVersion, String runtimeVersion,
                                     String sandboxMode, boolean toolAllowlistEnforced,
                                     boolean userConfigIsolated, boolean cancellationSupported) {
        this(profileVersion, "legacy-adapter", runtimeVersion, "legacy-matrix", sandboxMode,
                userConfigIsolated, toolAllowlistEnforced, userConfigIsolated,
                userConfigIsolated, userConfigIsolated, cancellationSupported, true);
    }

    public boolean supportsMcpToolIsolation() {
        return singleRunOverridesEnforced && toolAllowDenyEnforced && userConfigIsolated
                && projectConfigAbsent && repoSkillIsolationEnforced;
    }

    public RuntimeEnforcementProfile withSandboxMode(String restrictedSandboxMode) {
        return new RuntimeEnforcementProfile(profileVersion, adapterVersion, runtimeVersion,
                compatibilityMatrixVersion, restrictedSandboxMode, singleRunOverridesEnforced,
                toolAllowDenyEnforced, userConfigIsolated, projectConfigAbsent,
                repoSkillIsolationEnforced, processTreeCancellationEnforced);
    }

    public static RuntimeEnforcementProfile legacy() {
        return new RuntimeEnforcementProfile("m2-legacy", "unknown-adapter", "unknown",
                "unknown-matrix", "unknown", false, false, false, false, false, false);
    }
}
