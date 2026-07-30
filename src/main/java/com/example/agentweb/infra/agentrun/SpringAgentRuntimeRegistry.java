package com.example.agentweb.infra.agentrun;

import com.example.agentweb.app.agentrun.port.AgentRuntimeRegistry;
import com.example.agentweb.domain.agentrun.AgentRuntimeAvailability;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.nativeagent.NativeRuntimeRegistration;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Spring bean registration projected as provider-neutral catalog availability.
 *
 * @author alex
 * @since 2026-07-29
 */
@Component
public class SpringAgentRuntimeRegistry implements AgentRuntimeRegistry {

    private final Optional<NativeRuntimeRegistration> nativeRegistration;

    public SpringAgentRuntimeRegistry(Optional<NativeRuntimeRegistration> nativeRegistration) {
        this.nativeRegistration = nativeRegistration;
    }

    @Override
    public List<AgentRuntimeAvailability> availability() {
        List<AgentRuntimeAvailability> result = new ArrayList<AgentRuntimeAvailability>();
        result.add(AgentRuntimeAvailability.availableEverywhere(AgentType.CODEX));
        result.add(AgentRuntimeAvailability.availableEverywhere(AgentType.CLAUDE));
        nativeRegistration.filter(registration ->
                !registration.operationalEnvironments().isEmpty()).ifPresent(registration ->
                result.add(AgentRuntimeAvailability.availableOn(
                        AgentType.NATIVE, registration.operationalEnvironments())));
        return result;
    }
}
