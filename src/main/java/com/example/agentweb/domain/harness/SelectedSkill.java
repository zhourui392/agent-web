package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * Skill 选择结果及其确定性原因。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class SelectedSkill {

    private final SkillPackage skillPackage;
    private final SkillSelectionReason reason;

    public SelectedSkill(SkillPackage skillPackage, SkillSelectionReason reason) {
        if (skillPackage == null || reason == null) {
            throw new IllegalArgumentException("selected skill and reason must not be null");
        }
        this.skillPackage = skillPackage;
        this.reason = reason;
    }
}
