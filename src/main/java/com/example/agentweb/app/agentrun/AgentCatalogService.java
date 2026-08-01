package com.example.agentweb.app.agentrun;

import com.example.agentweb.app.agentrun.port.AgentRuntimeRegistry;
import com.example.agentweb.domain.agentrun.AgentCatalog;
import com.example.agentweb.domain.agentrun.AgentSurface;
import com.example.agentweb.domain.shared.AgentType;
import org.springframework.stereotype.Service;

/**
 * Application facade for the effective agent catalog.
 *
 * @author alex
 * @since 2026-07-29
 */
@Service
public class AgentCatalogService {

    private final AgentRuntimeRegistry runtimeRegistry;

    public AgentCatalogService(AgentRuntimeRegistry runtimeRegistry) {
        this.runtimeRegistry = runtimeRegistry;
    }

    public AgentCatalog currentCatalog() {
        return new AgentCatalog(runtimeRegistry.availability());
    }

    public AgentType resolveChatSelection(String input, AgentType defaultType, String environment) {
        return currentCatalog().resolveChatSelection(input, defaultType, environment);
    }

    public AgentType requireDefaultEligible(AgentType type) {
        return currentCatalog().requireDefaultEligible(type);
    }

    public AgentType requireChatAvailable(AgentType type, String environment) {
        return currentCatalog().requireChatAvailable(type, environment);
    }

    public AgentType requireWorkbenchAvailable(
            AgentType type, String environment) {
        return currentCatalog().requireAvailableForSurface(
                type, AgentSurface.WORKBENCH, environment);
    }
}
