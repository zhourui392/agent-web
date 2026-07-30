package com.example.agentweb.domain.agentrun;

import com.example.agentweb.domain.shared.AgentType;

import java.util.EnumSet;

/**
 * Static product policy for known agent identities.
 *
 * @author alex
 * @since 2026-07-29
 */
public final class AgentOfferPolicy {

    private AgentOfferPolicy() {
    }

    public static AgentOffer offer(AgentType type, AgentRuntimeAvailability runtime) {
        return switch (type) {
            case CODEX -> general(type, "Codex", runtime);
            case CLAUDE -> general(type, "Claude", runtime);
            case NATIVE -> diagnosis(runtime);
        };
    }

    public static AgentType requireDefaultEligible(AgentType type) {
        if (type == null) {
            throw new AgentPolicyViolationException(
                    "AGENT_NOT_DEFAULT_ELIGIBLE", "Default agent must not be null");
        }
        offer(type, AgentRuntimeAvailability.unavailable(type)).requireDefaultEligible();
        return type;
    }

    public static AgentType requireSurfaceEligible(AgentType type, AgentSurface surface) {
        if (type == null) {
            throw new AgentPolicyViolationException(
                    "AGENT_SURFACE_UNAVAILABLE", "Agent type must not be null");
        }
        offer(type, AgentRuntimeAvailability.unavailable(type)).requireSurface(surface);
        return type;
    }

    public static boolean supportsSurface(AgentType type, AgentSurface surface) {
        return type != null
                && offer(type, AgentRuntimeAvailability.unavailable(type)).supportsSurface(surface);
    }

    private static AgentOffer general(AgentType type, String displayName,
                                      AgentRuntimeAvailability runtime) {
        return new AgentOffer(type, displayName, AgentPurpose.GENERAL,
                EnumSet.of(AgentSurface.CHAT, AgentSurface.DEFAULT, AgentSurface.SCHEDULE,
                        AgentSurface.WORKFLOW, AgentSurface.HARNESS, AgentSurface.REFINERY),
                true, true, runtime);
    }

    private static AgentOffer diagnosis(AgentRuntimeAvailability runtime) {
        return new AgentOffer(AgentType.NATIVE, "诊断 Agent", AgentPurpose.DIAGNOSIS,
                EnumSet.of(AgentSurface.CHAT), true, false, runtime);
    }
}
