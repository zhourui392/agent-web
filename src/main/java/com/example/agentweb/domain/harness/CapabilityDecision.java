package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * Skill 能力请求与显式 Grant 求交后的决策。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class CapabilityDecision {

    private final String skillId;
    private final CapabilityRequest request;
    private final boolean authorized;
    private final String reason;

    public CapabilityDecision(String skillId, CapabilityRequest request, boolean authorized, String reason) {
        this.skillId = DomainText.require(skillId, "capability skill id", 120);
        if (request == null) {
            throw new IllegalArgumentException("capability request must not be null");
        }
        this.request = request;
        this.authorized = authorized;
        this.reason = DomainText.require(reason, "capability decision reason", 120);
    }
}
