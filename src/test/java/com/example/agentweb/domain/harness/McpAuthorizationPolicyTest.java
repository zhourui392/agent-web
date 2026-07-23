package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MCP 信任、阶段、Grant、只读和 Runtime Enforcement 求交测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class McpAuthorizationPolicyTest {

    private final McpAuthorizationPolicy policy = new McpAuthorizationPolicy();

    @Test
    void shouldSelectReadOnlyServerWhenEveryAuthorizationDimensionAllowsIt() {
        McpSelection selection = policy.select(request(
                Collections.singleton("reader"), Collections.<String>emptySet(),
                Collections.singleton("reader"), Collections.singleton("reader"), enforced()),
                Collections.singletonList(server("reader", CapabilityAccess.READ,
                        HarnessStage.ANALYSIS)));

        assertEquals(1, selection.getSelected().size());
        assertEquals("reader", selection.getSelected().get(0).getId());
        assertEquals(Collections.singletonList("search"),
                selection.getSelected().get(0).getEnabledToolNames());
        assertEquals(Collections.emptyList(),
                selection.getSelected().get(0).getDisabledToolNames());
        assertEquals(10, selection.getSelected().get(0).getStartupTimeoutSeconds());
        assertEquals(30, selection.getSelected().get(0).getToolTimeoutSeconds());
        assertEquals(false, selection.getSelected().get(0).isRequired());
        assertEquals(0, selection.getRejected().size());
    }

    @Test
    void shouldEnableReadToolsAndExplicitlyDisableWriteTools() {
        McpServerDefinition mixed = server("mixed", HarnessStage.ANALYSIS,
                new McpCapability("update", McpCapabilityType.TOOL, CapabilityAccess.WRITE),
                new McpCapability("search", McpCapabilityType.TOOL, CapabilityAccess.READ));

        McpSelection selection = policy.select(request(
                Collections.singleton("mixed"), Collections.singleton("mixed"),
                Collections.singleton("mixed"), Collections.singleton("mixed"), enforced()),
                Collections.singletonList(mixed));

        SelectedMcpServer selected = selection.getSelected().get(0);
        assertEquals(true, selected.isRequired());
        assertEquals(Collections.singletonList("search"), selected.getEnabledToolNames());
        assertEquals(Collections.singletonList("update"), selected.getDisabledToolNames());
        assertEquals(10, selected.getStartupTimeoutSeconds());
        assertEquals(30, selected.getToolTimeoutSeconds());
    }

    @Test
    void shouldRejectWriteOnlyResourceMissingGrantOrInsufficientEnforcement() {
        McpSelection writeRejected = policy.select(request(
                Collections.singleton("writer"), Collections.<String>emptySet(),
                Collections.singleton("writer"), Collections.singleton("writer"), enforced()),
                Collections.singletonList(server("writer", HarnessStage.ANALYSIS,
                        new McpCapability("update", McpCapabilityType.TOOL,
                                CapabilityAccess.WRITE))));
        McpSelection resourceRejected = policy.select(request(
                Collections.singleton("resource"), Collections.<String>emptySet(),
                Collections.singleton("resource"), Collections.singleton("resource"), enforced()),
                Collections.singletonList(server("resource", HarnessStage.ANALYSIS,
                        new McpCapability("document", McpCapabilityType.RESOURCE,
                                CapabilityAccess.READ))));
        McpSelection grantRejected = policy.select(request(
                Collections.singleton("reader"), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.singleton("reader"), enforced()),
                Collections.singletonList(server("reader", CapabilityAccess.READ,
                        HarnessStage.ANALYSIS)));
        McpSelection enforcementRejected = policy.select(request(
                Collections.singleton("reader"), Collections.<String>emptySet(),
                Collections.singleton("reader"), Collections.singleton("reader"),
                enforcement(false)),
                Collections.singletonList(server("reader", CapabilityAccess.READ,
                        HarnessStage.ANALYSIS)));

        assertEquals(McpRejectionReason.WRITE_NOT_SUPPORTED,
                writeRejected.getRejected().get(0).getReason());
        assertEquals(McpRejectionReason.RESOURCE_NOT_SUPPORTED,
                resourceRejected.getRejected().get(0).getReason());
        assertEquals(McpRejectionReason.NOT_GRANTED,
                grantRejected.getRejected().get(0).getReason());
        assertEquals(McpRejectionReason.ENFORCEMENT_INSUFFICIENT,
                enforcementRejected.getRejected().get(0).getReason());
    }

    @Test
    void requiredServerShouldFailClosedWhenMissingOrDenied() {
        McpSelectionRequest missing = request(Collections.singleton("missing"),
                Collections.singleton("missing"), Collections.singleton("missing"),
                Collections.singleton("missing"), enforced());
        McpSelectionRequest denied = request(Collections.singleton("reader"),
                Collections.singleton("reader"), Collections.singleton("reader"),
                Collections.<String>emptySet(), enforced());

        assertEquals("MCP_NOT_FOUND", assertThrows(CapabilityResolutionException.class,
                () -> policy.select(missing, Collections.<McpServerDefinition>emptyList())).getCode());
        assertEquals("MCP_REQUIRED_DENIED", assertThrows(CapabilityResolutionException.class,
                () -> policy.select(denied, Collections.singletonList(server(
                        "reader", CapabilityAccess.READ, HarnessStage.ANALYSIS)))).getCode());
    }

    @Test
    void shouldSortServersAndToolNamesForStableSnapshotContent() {
        LinkedHashSet<String> requested = new LinkedHashSet<String>(Arrays.asList("zeta", "alpha"));
        McpSelection selection = policy.select(request(requested, Collections.<String>emptySet(),
                        requested, requested, enforced()),
                Arrays.asList(server("zeta", HarnessStage.ANALYSIS,
                                new McpCapability("z-read", McpCapabilityType.TOOL,
                                        CapabilityAccess.READ),
                                new McpCapability("a-read", McpCapabilityType.TOOL,
                                        CapabilityAccess.READ)),
                        server("alpha", CapabilityAccess.READ, HarnessStage.ANALYSIS)));

        assertEquals(Arrays.asList("alpha", "zeta"), Arrays.asList(
                selection.getSelected().get(0).getId(), selection.getSelected().get(1).getId()));
        assertEquals(Arrays.asList("a-read", "z-read"),
                selection.getSelected().get(1).getEnabledToolNames());
    }

    private McpSelectionRequest request(java.util.Set<String> requested,
                                        java.util.Set<String> required,
                                        java.util.Set<String> granted,
                                        java.util.Set<String> environmentAllowed,
                                        RuntimeEnforcementProfile enforcement) {
        return new McpSelectionRequest(HarnessStage.ANALYSIS, AgentRuntime.CODEX,
                requested, required, granted, environmentAllowed, enforcement);
    }

    private RuntimeEnforcementProfile enforced() {
        return enforcement(true);
    }

    private RuntimeEnforcementProfile enforcement(boolean toolAllowDeny) {
        return new RuntimeEnforcementProfile("codex-runtime-enforcement@2",
                "codex-harness-adapter@2", "0.145.0", "codex-m0@2026-07-22",
                "read-only", true, toolAllowDeny, true, true, true, true);
    }

    private McpServerDefinition server(String id, CapabilityAccess access, HarnessStage stage) {
        return server(id, stage,
                new McpCapability("search", McpCapabilityType.TOOL, access));
    }

    private McpServerDefinition server(String id, HarnessStage stage,
                                       McpCapability... capabilities) {
        return new McpServerDefinition(id, "1.0.0", id + " server",
                Collections.singleton(stage), Collections.singleton(AgentRuntime.CODEX),
                Arrays.asList("fake-mcp", "--stdio"),
                Arrays.asList(capabilities), Collections.<McpSecretReference>emptyList(),
                10, 30, HarnessHashing.sha256(id));
    }
}
