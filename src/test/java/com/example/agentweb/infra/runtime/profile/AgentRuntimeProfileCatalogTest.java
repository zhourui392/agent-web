package com.example.agentweb.infra.runtime.profile;

import com.example.agentweb.app.runtime.port.AgentRuntimeSurface;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.RunMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeProfileCatalogTest {

    @Test
    void shouldSelectTheOnlyEnabledProfileForSurfaceAndRunMode() {
        AgentRuntimeProfile profile = profile("codex-a", "https://one.example", "m1");
        AgentRuntimeProfileCatalog catalog = new AgentRuntimeProfileCatalog(
                List.of(profile));

        AgentRuntimeProfile selected = catalog.select(
                AgentType.CODEX, AgentRuntimeSurface.CHAT, RunMode.DISCUSS_READ_ONLY,
                null, null, null);

        assertEquals("codex-a", selected.getProfileId());
        assertEquals("https://one.example", selected.getEndpoint());
    }

    @Test
    void shouldRejectAmbiguousSelection() {
        AgentRuntimeProfileCatalog catalog = new AgentRuntimeProfileCatalog(List.of(
                profile("codex-a", "https://one.example", "m1"),
                profile("codex-b", "https://two.example", "m2")));

        assertThrows(AgentRuntimeProfileSelectionException.class, () -> catalog.select(
                AgentType.CODEX, AgentRuntimeSurface.CHAT, RunMode.DISCUSS_READ_ONLY,
                null, null, null));
    }

    @Test
    void shouldRejectModelOutsideAllowList() {
        AgentRuntimeProfile profile = profile("codex-a", "https://one.example", "m1");
        AgentRuntimeProfileCatalog catalog = new AgentRuntimeProfileCatalog(List.of(profile));

        assertThrows(AgentRuntimeProfileSelectionException.class, () -> catalog.select(
                AgentType.CODEX, AgentRuntimeSurface.CHAT, RunMode.DISCUSS_READ_ONLY,
                null, "m2", null));
    }

    @Test
    void shouldRejectNativeReasoningOverrideUntilAgentKitSupportsIt() {
        AgentRuntimeProfile profile = new AgentRuntimeProfile(
                "native-a", AgentType.NATIVE, "https://native.example", "key",
                "diagnosis-model", Set.of("diagnosis-model"), "medium",
                Set.of("low", "medium", "high"), "test",
                Set.of(AgentRuntimeSurface.CHAT), Set.of(RunMode.DISCUSS_READ_ONLY), true);
        AgentRuntimeProfileCatalog catalog = new AgentRuntimeProfileCatalog(List.of(profile));

        assertThrows(AgentRuntimeProfileSelectionException.class, () -> catalog.select(
                AgentType.NATIVE, AgentRuntimeSurface.CHAT, RunMode.DISCUSS_READ_ONLY,
                null, null, "high"));
    }

    private AgentRuntimeProfile profile(String id, String endpoint, String model) {
        return new AgentRuntimeProfile(id, AgentType.CODEX, endpoint, "key",
                model, Set.of(model), "medium", Set.of("low", "medium", "high"),
                null, Set.of(AgentRuntimeSurface.CHAT),
                Set.of(RunMode.DISCUSS_READ_ONLY), true);
    }
}
