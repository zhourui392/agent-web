package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 完整 Skill 选择、拒绝和能力授权结果。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class SkillSelection {

    private final List<SelectedSkill> selected;
    private final List<RejectedSkill> rejected;
    private final List<CapabilityDecision> capabilityDecisions;

    public SkillSelection(List<SelectedSkill> selected, List<RejectedSkill> rejected,
                          List<CapabilityDecision> capabilityDecisions) {
        this.selected = immutable(selected, "selected skills");
        this.rejected = immutable(rejected, "rejected skills");
        this.capabilityDecisions = immutable(capabilityDecisions, "capability decisions");
    }

    public List<String> selectedSkillIds() {
        List<String> ids = new ArrayList<String>();
        for (SelectedSkill skill : selected) {
            ids.add(skill.getSkillPackage().getManifest().getId());
        }
        return Collections.unmodifiableList(ids);
    }

    public RuntimeEnforcementProfile enforceRuntimeProfile(
            HarnessStage stage, RuntimeEnforcementProfile maximum) {
        if (stage == null || maximum == null) {
            throw new IllegalArgumentException("stage and runtime enforcement maximum are required");
        }
        if (stage == HarnessStage.IMPLEMENTATION && hasAuthorizedWorkspaceWrite()) {
            if (!"workspace-write".equals(maximum.getSandboxMode())) {
                throw new CapabilityResolutionException("RUNTIME_WRITE_UNAVAILABLE",
                        "implementation WRITE grant cannot be enforced by runtime");
            }
            return maximum;
        }
        return "read-only".equals(maximum.getSandboxMode())
                ? maximum : maximum.withSandboxMode("read-only");
    }

    private boolean hasAuthorizedWorkspaceWrite() {
        for (CapabilityDecision decision : capabilityDecisions) {
            CapabilityRequest request = decision.getRequest();
            if (decision.isAuthorized() && request.getKind() == CapabilityKind.FILE
                    && request.getAccess() == CapabilityAccess.WRITE
                    && "workspace".equals(request.getResource())) {
                return true;
            }
        }
        return false;
    }

    private <T> List<T> immutable(List<T> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not be null or contain null");
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
