package com.example.agentweb.app.agentrun;

import com.example.agentweb.app.agentrun.port.AgentRuntimeRegistry;
import com.example.agentweb.domain.agentrun.AgentCatalog;
import com.example.agentweb.domain.agentrun.AgentRuntimeAvailability;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentCatalog 应用编排测试。
 *
 * @author alex
 * @since 2026-07-29
 */
class AgentCatalogServiceTest {

    @Test
    void currentCatalog_shouldUseLatestRuntimeRegistrySnapshot() {
        AgentRuntimeRegistry registry = mock(AgentRuntimeRegistry.class);
        when(registry.availability()).thenReturn(Arrays.asList(
                AgentRuntimeAvailability.availableEverywhere(AgentType.CODEX),
                AgentRuntimeAvailability.availableEverywhere(AgentType.CLAUDE),
                AgentRuntimeAvailability.availableOn(
                        AgentType.NATIVE, Collections.singleton("test"))));
        AgentCatalogService service = new AgentCatalogService(registry);

        AgentCatalog catalog = service.currentCatalog();

        assertEquals(AgentType.NATIVE,
                catalog.resolveChatSelection("NATIVE", AgentType.CODEX, "test"));
    }
}
