package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.Objects;

/**
 * Skill 的精确版本必需依赖。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class SkillDependency {

    private final String skillId;
    private final String version;

    public SkillDependency(String skillId, String version) {
        this.skillId = DomainText.require(skillId, "dependency skill id", 120);
        this.version = DomainText.require(version, "dependency version", 60);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SkillDependency)) {
            return false;
        }
        SkillDependency that = (SkillDependency) other;
        return skillId.equals(that.skillId) && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(skillId, version);
    }
}
