package com.example.agentweb.domain.agentrun;

import com.example.agentweb.domain.shared.AgentType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Effective catalog and selection guard for all known agent identities.
 *
 * @author alex
 * @since 2026-07-29
 */
public final class AgentCatalog {

    private final Map<AgentType, AgentOffer> offers;

    public AgentCatalog(Collection<AgentRuntimeAvailability> runtimes) {
        EnumMap<AgentType, AgentRuntimeAvailability> availability = availabilityByType(runtimes);
        EnumMap<AgentType, AgentOffer> result = new EnumMap<AgentType, AgentOffer>(AgentType.class);
        for (AgentType type : AgentType.values()) {
            AgentRuntimeAvailability runtime = availability.getOrDefault(
                    type, AgentRuntimeAvailability.unavailable(type));
            result.put(type, AgentOfferPolicy.offer(type, runtime));
        }
        this.offers = Collections.unmodifiableMap(result);
    }

    public AgentOffer offer(AgentType type) {
        if (type == null) {
            throw new AgentPolicyViolationException("UNKNOWN_AGENT_TYPE", "agentType is blank");
        }
        return offers.get(type);
    }

    public List<AgentOffer> offers() {
        return new ArrayList<AgentOffer>(offers.values());
    }

    public List<AgentType> defaultEligibleTypes() {
        List<AgentType> result = new ArrayList<AgentType>();
        for (AgentOffer offer : offers.values()) {
            if (offer.isDefaultEligible()) {
                result.add(offer.getType());
            }
        }
        return Collections.unmodifiableList(result);
    }

    public AgentType resolveChatSelection(String input, AgentType defaultType, String environment) {
        AgentType selected = hasText(input) ? parseInput(input) : requireDefault(defaultType);
        offer(selected).requireChatSelectable(environment);
        return selected;
    }

    public AgentType requireDefaultEligible(AgentType type) {
        offer(type).requireDefaultEligible();
        return type;
    }

    public AgentType requireChatAvailable(AgentType type, String environment) {
        offer(type).requireChatSelectable(environment);
        return type;
    }

    private AgentType requireDefault(AgentType defaultType) {
        requireDefaultEligible(defaultType);
        return defaultType;
    }

    private AgentType parseInput(String input) {
        try {
            return AgentType.parseKnown(input);
        } catch (IllegalArgumentException ex) {
            throw new AgentPolicyViolationException("UNKNOWN_AGENT_TYPE", ex.getMessage());
        }
    }

    private EnumMap<AgentType, AgentRuntimeAvailability> availabilityByType(
            Collection<AgentRuntimeAvailability> runtimes) {
        EnumMap<AgentType, AgentRuntimeAvailability> result =
                new EnumMap<AgentType, AgentRuntimeAvailability>(AgentType.class);
        if (runtimes == null) {
            return result;
        }
        for (AgentRuntimeAvailability runtime : runtimes) {
            if (result.put(runtime.getType(), runtime) != null) {
                throw new IllegalArgumentException("duplicate runtime: " + runtime.getType());
            }
        }
        return result;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
