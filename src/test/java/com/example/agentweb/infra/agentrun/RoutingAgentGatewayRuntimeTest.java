package com.example.agentweb.infra.agentrun;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.agentrun.port.AgentRuntime;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoutingAgentGatewayRuntimeTest {

    @Test
    void shouldRouteStartStopAndObserveToRuntimeOwningHandle() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        RuntimeHandle handle = new RuntimeHandle("run-1", "handle-1");
        when(runtime.supportedTypes()).thenReturn(EnumSet.of(AgentType.CODEX));
        when(runtime.start(any(AgentExecutionPlan.class), any(RuntimeEventSink.class)))
                .thenReturn(handle);
        RuntimeObservation observation = RuntimeObservation.running(handle, 0L);
        when(runtime.observe(handle)).thenReturn(observation);
        RoutingAgentGateway gateway = new RoutingAgentGateway(List.of(runtime));
        AgentExecutionPlan plan = mock(AgentExecutionPlan.class);
        var selection = mock(com.example.agentweb.app.runtime.port.RuntimeSelection.class);
        when(plan.getRuntimeSelection()).thenReturn(selection);
        when(selection.getAgentType()).thenReturn(AgentType.CODEX);

        assertEquals(handle, gateway.start(plan, event -> { }));
        gateway.requestStop(handle);
        assertEquals(observation, gateway.observe(handle));
        verify(runtime).requestStop(handle);
    }
}
