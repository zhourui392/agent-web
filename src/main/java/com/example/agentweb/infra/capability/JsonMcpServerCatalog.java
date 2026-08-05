package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.McpSecretReference;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.McpTransport;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 管理后台平台格式 MCP JSON 的严格解析器。
 *
 * @author alex
 * @since 2026-08-05
 */
public class JsonMcpServerCatalog {

    private static final String SCHEMA = "workbench-mcp-catalog@1";
    private static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_TOOL_TIMEOUT_SECONDS = 60;
    private static final Pattern ENVIRONMENT_VARIABLE =
            Pattern.compile("[A-Z_][A-Z0-9_]*");
    private static final Pattern SECRET_REFERENCE =
            Pattern.compile("environment:[A-Z_][A-Z0-9_]*");
    private static final Set<String> ROOT_FIELDS = setOf("schema", "servers");
    private static final Set<String> SERVER_FIELDS = setOf(
            "identifier", "version", "displayName", "description", "transport",
            "command", "arguments", "workingDirectory", "endpoint",
            "environmentVariables", "access", "compatibleRuntimes",
            "startupTimeoutSeconds", "toolTimeoutSeconds");

    private final ObjectMapper objectMapper;

    public JsonMcpServerCatalog(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("MCP JSON object mapper must not be null");
        }
        this.objectMapper = objectMapper;
    }

    public ParsedMcpServerCatalog parse(String json) {
        ObjectNode root = object(json);
        requireOnlyFields(root, ROOT_FIELDS);
        if (!SCHEMA.equals(text(root, "schema"))) {
            throw failure("CATALOG_MCP_SCHEMA_UNSUPPORTED",
                    "unsupported MCP catalog schema");
        }
        JsonNode serversNode = root.get("servers");
        if (!(serversNode instanceof ArrayNode)) {
            throw failure("CATALOG_MCP_JSON_INVALID", "MCP servers must be an array");
        }
        List<McpServerDefinition> definitions = definitions((ArrayNode) serversNode);
        return new ParsedMcpServerCatalog(canonical(root), definitions);
    }

    private ObjectNode object(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw failure("CATALOG_MCP_JSON_INVALID", "MCP JSON must not be blank");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!(root instanceof ObjectNode)) {
                throw failure("CATALOG_MCP_JSON_INVALID", "MCP JSON root must be an object");
            }
            return (ObjectNode) root;
        } catch (CapabilityCatalogException failure) {
            throw failure;
        } catch (JsonProcessingException failure) {
            throw new CapabilityCatalogException(
                    "CATALOG_MCP_JSON_INVALID", "cannot parse MCP JSON", failure);
        }
    }

    private List<McpServerDefinition> definitions(ArrayNode nodes) {
        List<McpServerDefinition> definitions = new ArrayList<McpServerDefinition>();
        Set<String> keys = new HashSet<String>();
        for (JsonNode node : nodes) {
            if (!(node instanceof ObjectNode)) {
                throw failure("CATALOG_MCP_JSON_INVALID",
                        "MCP server definition must be an object");
            }
            McpServerDefinition definition = definition((ObjectNode) node);
            String key = definition.getId() + "\u0000" + definition.getVersion();
            if (!keys.add(key)) {
                throw failure("CATALOG_MCP_DUPLICATE_DEFINITION",
                        "MCP identifier and version must be unique");
            }
            definitions.add(definition);
        }
        definitions.sort(Comparator.comparing(McpServerDefinition::getId)
                .thenComparing(McpServerDefinition::getVersion));
        return Collections.unmodifiableList(definitions);
    }

    private McpServerDefinition definition(ObjectNode node) {
        requireOnlyFields(node, SERVER_FIELDS);
        String identifier = text(node, "identifier");
        String version = text(node, "version");
        String displayName = text(node, "displayName");
        String description = text(node, "description");
        McpTransport transport = enumValue(
                text(node, "transport"), McpTransport.class, "MCP transport");
        TransportConfiguration transportConfiguration = transport(node, transport);
        List<McpSecretReference> secrets = secrets(node.get("environmentVariables"));
        Set<String> runtimes = stringSet(node, "compatibleRuntimes");
        CapabilityAccess access = access(text(node, "access"));
        int startupTimeout = positiveInt(
                node, "startupTimeoutSeconds", DEFAULT_STARTUP_TIMEOUT_SECONDS);
        int toolTimeout = positiveInt(
                node, "toolTimeoutSeconds", DEFAULT_TOOL_TIMEOUT_SECONDS);
        return McpServerDefinition.managed(
                identifier, version, displayName, description, runtimes,
                transportConfiguration.command, secrets, transport,
                transportConfiguration.workingDirectory,
                transportConfiguration.endpoint, access,
                startupTimeout, toolTimeout,
                CanonicalHashing.sha256(canonical(node)));
    }

    private TransportConfiguration transport(ObjectNode node, McpTransport transport) {
        if (transport == McpTransport.STDIO) {
            if (node.has("endpoint")) {
                throw failure("CATALOG_MCP_TRANSPORT_INVALID",
                        "STDIO MCP must not declare endpoint");
            }
            String command = text(node, "command");
            List<String> arguments = stringList(node, "arguments", false);
            String workingDirectory = text(node, "workingDirectory");
            if (!java.nio.file.Path.of(workingDirectory).isAbsolute()) {
                throw failure("CATALOG_MCP_TRANSPORT_INVALID",
                        "STDIO MCP working directory must be absolute");
            }
            List<String> commandLine = new ArrayList<String>();
            commandLine.add(command);
            commandLine.addAll(arguments);
            return new TransportConfiguration(
                    Collections.unmodifiableList(commandLine), workingDirectory, "");
        }
        if (node.has("command") || node.has("arguments") || node.has("workingDirectory")) {
            throw failure("CATALOG_MCP_TRANSPORT_INVALID",
                    "STREAMABLE_HTTP MCP must not declare STDIO fields");
        }
        return new TransportConfiguration(
                Collections.emptyList(), "", endpoint(text(node, "endpoint")));
    }

    private List<McpSecretReference> secrets(JsonNode node) {
        if (node == null || node.isNull()) {
            return Collections.emptyList();
        }
        if (!(node instanceof ObjectNode)) {
            throw failure("CATALOG_MCP_JSON_INVALID",
                    "MCP environmentVariables must be an object");
        }
        List<McpSecretReference> references = new ArrayList<McpSecretReference>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!ENVIRONMENT_VARIABLE.matcher(field.getKey()).matches()) {
                throw failure("CATALOG_MCP_JSON_INVALID",
                        "invalid MCP environment variable name");
            }
            if (!(field.getValue() instanceof ObjectNode)) {
                throw failure("CATALOG_MCP_SECRET_PLAINTEXT_FORBIDDEN",
                        "MCP environment values must use secretReference");
            }
            ObjectNode referenceNode = (ObjectNode) field.getValue();
            requireOnlyFields(referenceNode, Collections.singleton("secretReference"));
            String reference = text(referenceNode, "secretReference");
            if (!SECRET_REFERENCE.matcher(reference).matches()) {
                throw failure("CATALOG_MCP_SECRET_REFERENCE_INVALID",
                        "MCP secret reference must use environment:VARIABLE");
            }
            references.add(new McpSecretReference(field.getKey(), reference));
        }
        references.sort(Comparator.comparing(McpSecretReference::getEnvironmentVariable));
        return Collections.unmodifiableList(references);
    }

    private Set<String> stringSet(ObjectNode node, String field) {
        List<String> values = stringList(node, field, true);
        Set<String> normalized = new java.util.TreeSet<String>();
        for (String value : values) {
            normalized.add(value.toUpperCase(java.util.Locale.ROOT));
        }
        return Collections.unmodifiableSet(normalized);
    }

    private List<String> stringList(ObjectNode node, String field, boolean required) {
        JsonNode value = node.get(field);
        if (value == null) {
            if (required) {
                throw failure("CATALOG_MCP_JSON_INVALID",
                        "missing MCP array field: " + field);
            }
            return Collections.emptyList();
        }
        if (!(value instanceof ArrayNode)) {
            throw failure("CATALOG_MCP_JSON_INVALID",
                    "MCP field must be an array: " + field);
        }
        List<String> values = new ArrayList<String>();
        for (JsonNode element : value) {
            if (!element.isTextual() || element.asText().trim().isEmpty()) {
                throw failure("CATALOG_MCP_JSON_INVALID",
                        "MCP array contains invalid value: " + field);
            }
            values.add(element.asText().trim());
        }
        if (required && values.isEmpty()) {
            throw failure("CATALOG_MCP_JSON_INVALID",
                    "MCP array must not be empty: " + field);
        }
        return values;
    }

    private CapabilityAccess access(String value) {
        if ("READ_ONLY".equals(value)) {
            return CapabilityAccess.READ;
        }
        if ("READ_WRITE".equals(value)) {
            return CapabilityAccess.WRITE;
        }
        throw failure("CATALOG_MCP_ACCESS_INVALID",
                "MCP access must be READ_ONLY or READ_WRITE");
    }

    private int positiveInt(ObjectNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.canConvertToInt() || value.intValue() < 1 || value.intValue() > 3600) {
            throw failure("CATALOG_MCP_JSON_INVALID",
                    "MCP timeout must be between 1 and 3600: " + field);
        }
        return value.intValue();
    }

    private String endpoint(String value) {
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw failure("CATALOG_MCP_TRANSPORT_INVALID",
                        "MCP endpoint must use HTTP or HTTPS");
            }
            return uri.toString();
        } catch (URISyntaxException failure) {
            throw new CapabilityCatalogException("CATALOG_MCP_TRANSPORT_INVALID",
                    "MCP endpoint is invalid", failure);
        }
    }

    private String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            throw failure("CATALOG_MCP_JSON_INVALID",
                    "MCP text field must not be blank: " + field);
        }
        return value.asText().trim();
    }

    private <E extends Enum<E>> E enumValue(
            String value, Class<E> type, String field) {
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new CapabilityCatalogException("CATALOG_MCP_JSON_INVALID",
                    "unknown " + field, failure);
        }
    }

    private void requireOnlyFields(ObjectNode node, Set<String> allowed) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw failure("CATALOG_MCP_JSON_FIELD_UNKNOWN",
                        "unsupported MCP JSON field: " + field);
            }
        }
    }

    private String canonical(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(canonicalNode(node));
        } catch (JsonProcessingException failure) {
            throw new CapabilityCatalogException("CATALOG_MCP_JSON_INVALID",
                    "cannot canonicalize MCP JSON", failure);
        }
    }

    private JsonNode canonicalNode(JsonNode node) {
        if (node instanceof ObjectNode) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> fields = new ArrayList<String>();
            node.fieldNames().forEachRemaining(fields::add);
            Collections.sort(fields);
            for (String field : fields) {
                sorted.set(field, canonicalNode(node.get(field)));
            }
            return sorted;
        }
        if (node instanceof ArrayNode) {
            ArrayNode array = objectMapper.createArrayNode();
            for (JsonNode element : node) {
                array.add(canonicalNode(element));
            }
            return array;
        }
        return node.deepCopy();
    }

    private static Set<String> setOf(String... values) {
        Set<String> result = new HashSet<String>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    private static CapabilityCatalogException failure(String code, String message) {
        return new CapabilityCatalogException(code, message);
    }

    private static final class TransportConfiguration {

        private final List<String> command;
        private final String workingDirectory;
        private final String endpoint;

        private TransportConfiguration(
                List<String> command, String workingDirectory, String endpoint) {
            this.command = command;
            this.workingDirectory = workingDirectory;
            this.endpoint = endpoint;
        }
    }
}
