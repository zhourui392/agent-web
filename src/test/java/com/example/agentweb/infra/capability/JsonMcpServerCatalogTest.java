package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.McpTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 管理后台 MCP JSON Catalog 测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class JsonMcpServerCatalogTest {

    @Test
    void should_ParseStdioServer_When_ConfigurationMatchesSchema() {
        // Given
        JsonMcpServerCatalog catalog = new JsonMcpServerCatalog(new ObjectMapper());

        // When
        McpServerDefinition server = catalog.parse(validStdioJson()).getDefinitions().get(0);

        // Then
        assertEquals("repository-query", server.getId());
        assertEquals(McpTransport.STDIO, server.getTransport());
        assertEquals(CapabilityAccess.READ, server.getMaximumAccess());
        assertEquals("/opt/agent-tools/repository-query", server.getWorkingDirectory());
        assertEquals(java.util.List.of("node", "server.mjs"), server.getCommand());
        assertEquals(1, server.getSecretReferences().size());
    }

    @Test
    void should_ProduceStableCanonicalJson_When_ObjectFieldOrderChanges() {
        // Given
        JsonMcpServerCatalog catalog = new JsonMcpServerCatalog(new ObjectMapper());
        String first = "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[]}";
        String second = "{\"servers\":[],\"schema\":\"workbench-mcp-catalog@1\"}";

        // When / Then
        assertEquals(catalog.parse(first).getCanonicalJson(),
                catalog.parse(second).getCanonicalJson());
    }

    @Test
    void should_ParseStreamableHttpServer_When_EndpointIsDeclared() {
        // Given
        String json = "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[{"
                + "\"identifier\":\"remote\",\"version\":\"1\","
                + "\"displayName\":\"Remote\",\"description\":\"Remote MCP\","
                + "\"transport\":\"STREAMABLE_HTTP\","
                + "\"endpoint\":\"https://mcp.example.test/api\","
                + "\"environmentVariables\":{},\"access\":\"READ_ONLY\","
                + "\"compatibleRuntimes\":[\"CODEX\"]}]}";

        // When
        McpServerDefinition server = new JsonMcpServerCatalog(
                new ObjectMapper()).parse(json).getDefinitions().get(0);

        // Then
        assertEquals(McpTransport.STREAMABLE_HTTP, server.getTransport());
        assertEquals("https://mcp.example.test/api", server.getEndpoint());
        assertEquals(0, server.getCommand().size());
    }

    @Test
    void should_RejectConfiguration_When_EnvironmentContainsPlaintextSecret() {
        // Given
        String json = validStdioJson().replace(
                "{\"secretReference\":\"environment:REPOSITORY_QUERY_ACCESS_TOKEN\"}",
                "\"plaintext-secret\"");

        // When
        CapabilityCatalogException failure = assertThrows(CapabilityCatalogException.class,
                () -> new JsonMcpServerCatalog(new ObjectMapper()).parse(json));

        // Then
        assertEquals("CATALOG_MCP_SECRET_PLAINTEXT_FORBIDDEN", failure.getCode());
    }

    @Test
    void should_RejectConfiguration_When_ServerContainsUnknownField() {
        // Given
        String json = validStdioJson().replace(
                "\"transport\":\"STDIO\"", "\"unknown\":true,\"transport\":\"STDIO\"");

        // When
        CapabilityCatalogException failure = assertThrows(CapabilityCatalogException.class,
                () -> new JsonMcpServerCatalog(new ObjectMapper()).parse(json));

        // Then
        assertEquals("CATALOG_MCP_JSON_FIELD_UNKNOWN", failure.getCode());
    }

    @Test
    void should_RejectConfiguration_When_IdentifierVersionIsDuplicated() {
        // Given
        String server = validStdioJson();
        String object = server.substring(server.indexOf("[{" ) + 1, server.lastIndexOf("]"));
        String duplicated = server.substring(0, server.lastIndexOf("]")) + "," + object + "]}";

        // When
        CapabilityCatalogException failure = assertThrows(CapabilityCatalogException.class,
                () -> new JsonMcpServerCatalog(new ObjectMapper()).parse(duplicated));

        // Then
        assertEquals("CATALOG_MCP_DUPLICATE_DEFINITION", failure.getCode());
    }

    private String validStdioJson() {
        return "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[{"
                + "\"identifier\":\"repository-query\",\"version\":\"1.0.0\","
                + "\"displayName\":\"Repository Query\","
                + "\"description\":\"Query repositories\",\"transport\":\"STDIO\","
                + "\"command\":\"node\",\"arguments\":[\"server.mjs\"],"
                + "\"workingDirectory\":\"/opt/agent-tools/repository-query\","
                + "\"environmentVariables\":{\"ACCESS_TOKEN\":{"
                + "\"secretReference\":\"environment:REPOSITORY_QUERY_ACCESS_TOKEN\"}},"
                + "\"access\":\"READ_ONLY\",\"compatibleRuntimes\":[\"CODEX\"],"
                + "\"startupTimeoutSeconds\":10,\"toolTimeoutSeconds\":30}]}";
    }
}
