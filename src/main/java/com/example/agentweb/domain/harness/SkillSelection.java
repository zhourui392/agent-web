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

    private <T> List<T> immutable(List<T> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not be null or contain null");
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
