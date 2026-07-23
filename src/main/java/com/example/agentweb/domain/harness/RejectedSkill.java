package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * 未选 Skill 及其可观测原因。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class RejectedSkill {

    private final String skillId;
    private final String version;
    private final SkillRejectionReason reason;

    public RejectedSkill(String skillId, String version, SkillRejectionReason reason) {
        this.skillId = DomainText.require(skillId, "rejected skill id", 120);
        this.version = DomainText.require(version, "rejected skill version", 60);
        if (reason == null) {
            throw new IllegalArgumentException("skill rejection reason must not be null");
        }
        this.reason = reason;
    }
}
