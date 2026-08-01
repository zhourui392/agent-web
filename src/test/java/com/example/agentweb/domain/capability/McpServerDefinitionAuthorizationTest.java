package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MCP Tool 最大访问级别到 exact allow/deny 的领域授权测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class McpServerDefinitionAuthorizationTest {

    @Test
    void readAccessEnablesOnlyReadToolsAndExplicitlyDisablesWrites() {
        McpToolAuthorization authorization = definition().authorizeTools(
                CapabilityAccess.READ);

        assertEquals(Collections.singletonList("read_repository"),
                authorization.getEnabledToolNames());
        assertEquals(Collections.singletonList("write_repository"),
                authorization.getDisabledToolNames());
    }

    @Test
    void writeAccessEnablesReadAndWriteToolsWithoutDenyList() {
        McpToolAuthorization authorization = definition().authorizeTools(
                CapabilityAccess.WRITE);

        assertEquals(Arrays.asList("read_repository", "write_repository"),
                authorization.getEnabledToolNames());
        assertEquals(Collections.emptyList(),
                authorization.getDisabledToolNames());
    }

    @Test
    void rejectsExecuteNoUsableToolAndUnsupportedResourceCapability() {
        assertThrows(IllegalArgumentException.class,
                () -> definition().authorizeTools(CapabilityAccess.EXECUTE));
        assertThrows(IllegalArgumentException.class,
                () -> definition(Collections.singletonList(new McpCapability(
                        "write_repository", McpCapabilityType.TOOL,
                        CapabilityAccess.WRITE))).authorizeTools(CapabilityAccess.READ));
        assertThrows(IllegalArgumentException.class,
                () -> definition(Collections.singletonList(new McpCapability(
                        "repository_resource", McpCapabilityType.RESOURCE,
                        CapabilityAccess.READ))).authorizeTools(CapabilityAccess.READ));
    }

    private McpServerDefinition definition() {
        return definition(Arrays.asList(
                new McpCapability("write_repository", McpCapabilityType.TOOL,
                        CapabilityAccess.WRITE),
                new McpCapability("read_repository", McpCapabilityType.TOOL,
                        CapabilityAccess.READ)));
    }

    private McpServerDefinition definition(
            java.util.List<McpCapability> capabilities) {
        return new McpServerDefinition(
                "repository-query", "1.0.0", "repository query",
                Collections.singleton("IMPLEMENT_TEST"),
                Collections.singleton("CODEX"),
                Arrays.asList("repository-mcp", "--stdio"), capabilities,
                Collections.emptyList(), 10, 30,
                CanonicalHashing.sha256("definition"));
    }
}
