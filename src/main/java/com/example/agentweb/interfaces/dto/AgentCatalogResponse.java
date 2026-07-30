package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.agentrun.AgentCatalog;
import lombok.Getter;

import java.util.List;

/**
 * Agent catalog response including the versioned global default.
 *
 * @author alex
 * @since 2026-07-29
 */
@Getter
public final class AgentCatalogResponse {

    private final String defaultAgent;
    private final long defaultVersion;
    private final List<AgentOfferResponse> agents;

    private AgentCatalogResponse(String defaultAgent, long defaultVersion,
                                 List<AgentOfferResponse> agents) {
        this.defaultAgent = defaultAgent;
        this.defaultVersion = defaultVersion;
        this.agents = agents;
    }

    public static AgentCatalogResponse from(AgentCatalog catalog, String defaultAgent,
                                            long defaultVersion) {
        List<AgentOfferResponse> offers = catalog.offers().stream()
                .map(AgentOfferResponse::from)
                .toList();
        return new AgentCatalogResponse(defaultAgent, defaultVersion, offers);
    }
}
