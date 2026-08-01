package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Workbench Run 准备所需的受管技术策略与执行限额。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunPreparationSettings {

    private final String capabilityPolicyVersion;
    private final String runtimeCompatibility;
    private final Set<SkillTrustSource> allowedSkillTrustSources;
    private final RuntimeLimits runtimeLimits;

    public WorkbenchRunPreparationSettings(
            String capabilityPolicyVersion,
            String runtimeCompatibility,
            Set<SkillTrustSource> allowedSkillTrustSources,
            RuntimeLimits runtimeLimits) {
        this.capabilityPolicyVersion = DomainText.require(
                capabilityPolicyVersion,
                "workbench capability policy version", 120);
        this.runtimeCompatibility = DomainText.require(
                runtimeCompatibility,
                "workbench runtime compatibility", 240);
        if (allowedSkillTrustSources == null
                || allowedSkillTrustSources.contains(null)
                || runtimeLimits == null
                || runtimeLimits.getTimeout().getSeconds() < 1L) {
            throw new IllegalArgumentException(
                    "workbench run preparation settings must be complete");
        }
        this.allowedSkillTrustSources = Collections.unmodifiableSet(
                new HashSet<SkillTrustSource>(allowedSkillTrustSources));
        this.runtimeLimits = runtimeLimits;
    }
}
