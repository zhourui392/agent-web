package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;

/**
 * 使用与 Run 相同的可信 Catalog 解析器生成 Effective Profile 预览。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class PhaseCapabilityPreviewResolver {

    private final PhaseCapabilityBindingResolver bindingResolver;
    private final String policyVersion;

    public PhaseCapabilityPreviewResolver(
            PhaseCapabilityBindingResolver bindingResolver,
            String policyVersion) {
        if (bindingResolver == null) {
            throw new IllegalArgumentException(
                    "phase capability binding resolver must not be null");
        }
        this.bindingResolver = bindingResolver;
        this.policyVersion = policyVersion;
    }

    public PhaseCapabilityPreview resolve(
            PhaseCapabilityProfile profile, CapabilityOverride override,
            AgentType agentType) {
        PhaseCapabilityResolutionPolicy policy =
                PhaseCapabilityResolutionPolicy.forProfilePreview(
                        policyVersion, profile.getPhase(), agentType);
        return PhaseCapabilityPreview.create(
                profile, override,
                bindingResolver.resolve(profile, override, policy));
    }
}
