package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.agentrun.AgentOffer;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * Public agent offer returned to the chat selector.
 *
 * @author alex
 * @since 2026-07-29
 */
@Getter
public final class AgentOfferResponse {

    private final String type;
    private final String displayName;
    private final String purpose;
    private final boolean available;
    private final boolean userSelectable;
    private final boolean defaultEligible;
    private final boolean allEnvironments;
    private final List<String> supportedEnvironments;

    private AgentOfferResponse(AgentOffer offer) {
        this.type = offer.getType().name();
        this.displayName = offer.getDisplayName();
        this.purpose = offer.getPurpose().name();
        this.available = offer.isAvailable();
        this.userSelectable = offer.isUserSelectable();
        this.defaultEligible = offer.isDefaultEligible();
        this.allEnvironments = offer.supportsAllEnvironments();
        this.supportedEnvironments = new ArrayList<String>(offer.getSupportedEnvironments());
    }

    public static AgentOfferResponse from(AgentOffer offer) {
        return new AgentOfferResponse(offer);
    }
}
