package com.example.agentweb.infra.capability;

import com.example.agentweb.config.capability.CapabilityCatalogProperties;
import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.McpCapability;
import com.example.agentweb.domain.capability.McpCapabilityType;
import com.example.agentweb.domain.capability.McpSecretReference;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.McpServerDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 管理员受控目录中的 MCP Server 可信 Catalog。
 *
 * @author alex
 * @since 2026-07-23
 */
@Component
public class FileSystemMcpServerCatalog implements McpServerCatalog {

    private static final String MANIFEST_SCHEMA_FIELD = "schemaVersion";
    private static final String SUPPORTED_SCHEMA_VERSION = "1";

    private final Path root;

    @Autowired
    public FileSystemMcpServerCatalog(CapabilityCatalogProperties properties) {
        this(Paths.get(properties.getMcpServerRoot()));
    }

    public FileSystemMcpServerCatalog(Path root) {
        this.root = root;
    }

    @Override
    public List<McpServerDefinition> discover() {
        Path realRoot = CapabilityCatalogFiles.realRoot(root);
        List<McpServerDefinition> definitions = new ArrayList<McpServerDefinition>();
        for (Path manifestPath : CapabilityCatalogFiles.manifests(realRoot)) {
            definitions.add(parse(realRoot, manifestPath));
        }
        definitions.sort(Comparator.comparing(McpServerDefinition::getId)
                .thenComparing(McpServerDefinition::getVersion));
        return Collections.unmodifiableList(definitions);
    }

    private McpServerDefinition parse(Path realRoot, Path manifestPath) {
        CapabilityCatalogFiles.CatalogFile manifest = CapabilityCatalogFiles.readManifest(
                realRoot, manifestPath);
        CatalogYaml yaml = CatalogYaml.parse(manifest.getBytes(), manifestPath.toString());
        if (!SUPPORTED_SCHEMA_VERSION.equals(yaml.requiredString(MANIFEST_SCHEMA_FIELD))) {
            throw new CapabilityCatalogException("CATALOG_SCHEMA_UNSUPPORTED",
                    "unsupported MCP catalog schema version");
        }
        return new McpServerDefinition(yaml.requiredString("id"),
                yaml.requiredString("version"), yaml.requiredString("description"),
                stringSet(yaml.requiredStringList("applicableUseCases"), "use case"),
                stringSet(yaml.stringList("runtimes"), "runtime"),
                yaml.stringList("command"), capabilities(yaml.mapList("capabilities")),
                secrets(yaml.mapList("secrets")),
                positiveInt(yaml.requiredString("startupTimeoutSeconds"),
                        "startupTimeoutSeconds"),
                positiveInt(yaml.requiredString("toolTimeoutSeconds"), "toolTimeoutSeconds"),
                CapabilityCatalogFiles.packageHash(Collections.singletonList(manifest)));
    }

    private List<McpCapability> capabilities(List<Map<String, Object>> values) {
        if (values.isEmpty()) {
            throw new CapabilityCatalogException("CATALOG_MANIFEST_INVALID",
                    "MCP capability list must not be empty");
        }
        List<McpCapability> capabilities = new ArrayList<McpCapability>();
        for (Map<String, Object> value : values) {
            capabilities.add(new McpCapability(CatalogYaml.requiredString(value, "id"),
                    enumValue(CatalogYaml.requiredString(value, "type"),
                            McpCapabilityType.class, "capability type"),
                    enumValue(CatalogYaml.requiredString(value, "access"),
                            CapabilityAccess.class, "capability access")));
        }
        return capabilities;
    }

    private List<McpSecretReference> secrets(List<Map<String, Object>> values) {
        List<McpSecretReference> references = new ArrayList<McpSecretReference>();
        for (Map<String, Object> value : values) {
            references.add(new McpSecretReference(
                    CatalogYaml.requiredString(value, "environmentVariable"),
                    CatalogYaml.requiredString(value, "reference")));
        }
        return references;
    }

    private Set<String> stringSet(List<String> values, String name) {
        if (values.isEmpty()) {
            throw new CapabilityCatalogException("CATALOG_MANIFEST_INVALID",
                    "MCP " + name + " list must not be empty");
        }
        Set<String> result = new LinkedHashSet<String>();
        for (String value : values) {
            result.add(value.toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private <E extends Enum<E>> E enumValue(String value, Class<E> type, String name) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CapabilityCatalogException("CATALOG_MANIFEST_INVALID",
                    "unknown MCP " + name + ": " + value, ex);
        }
    }

    private int positiveInt(String value, String field) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new CapabilityCatalogException("CATALOG_MANIFEST_INVALID",
                    "MCP " + field + " must be a positive integer", ex);
        }
    }

}
