package com.example.agentweb.infra.harness;

import com.example.agentweb.config.harness.HarnessCatalogProperties;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilityAccess;
import com.example.agentweb.domain.harness.HarnessCatalogException;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.McpCapability;
import com.example.agentweb.domain.harness.McpCapabilityType;
import com.example.agentweb.domain.harness.McpSecretReference;
import com.example.agentweb.domain.harness.McpServerCatalog;
import com.example.agentweb.domain.harness.McpServerDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 管理员受控目录中的 MCP Server 可信 Catalog。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class FileSystemMcpServerCatalog implements McpServerCatalog {

    private static final String MANIFEST_SCHEMA_FIELD = "schemaVersion";
    private static final String SUPPORTED_SCHEMA_VERSION = "1";

    private final Path root;

    @Autowired
    public FileSystemMcpServerCatalog(HarnessCatalogProperties properties) {
        this(Paths.get(properties.getMcpServerRoot()));
    }

    FileSystemMcpServerCatalog(Path root) {
        this.root = root;
    }

    @Override
    public List<McpServerDefinition> discover() {
        Path realRoot = HarnessCatalogFiles.realRoot(root);
        List<McpServerDefinition> definitions = new ArrayList<McpServerDefinition>();
        for (Path manifestPath : HarnessCatalogFiles.manifests(realRoot)) {
            definitions.add(parse(realRoot, manifestPath));
        }
        definitions.sort(Comparator.comparing(McpServerDefinition::getId)
                .thenComparing(McpServerDefinition::getVersion));
        return Collections.unmodifiableList(definitions);
    }

    private McpServerDefinition parse(Path realRoot, Path manifestPath) {
        HarnessCatalogFiles.CatalogFile manifest = HarnessCatalogFiles.readManifest(
                realRoot, manifestPath);
        CatalogYaml yaml = CatalogYaml.parse(manifest.getBytes(), manifestPath.toString());
        if (!SUPPORTED_SCHEMA_VERSION.equals(yaml.requiredString(MANIFEST_SCHEMA_FIELD))) {
            throw new HarnessCatalogException("CATALOG_SCHEMA_UNSUPPORTED",
                    "unsupported MCP catalog schema version");
        }
        return new McpServerDefinition(yaml.requiredString("id"),
                yaml.requiredString("version"), yaml.requiredString("description"),
                enumSet(yaml.stringList("stages"), HarnessStage.class, "stage"),
                enumSet(yaml.stringList("runtimes"), AgentRuntime.class, "runtime"),
                yaml.stringList("command"), capabilities(yaml.mapList("capabilities")),
                secrets(yaml.mapList("secrets")),
                positiveInt(yaml.requiredString("startupTimeoutSeconds"),
                        "startupTimeoutSeconds"),
                positiveInt(yaml.requiredString("toolTimeoutSeconds"), "toolTimeoutSeconds"),
                HarnessCatalogFiles.packageHash(Collections.singletonList(manifest)));
    }

    private List<McpCapability> capabilities(List<Map<String, Object>> values) {
        if (values.isEmpty()) {
            throw new HarnessCatalogException("CATALOG_MANIFEST_INVALID",
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

    private <E extends Enum<E>> Set<E> enumSet(List<String> values, Class<E> type, String name) {
        if (values.isEmpty()) {
            throw new HarnessCatalogException("CATALOG_MANIFEST_INVALID",
                    "MCP " + name + " list must not be empty");
        }
        Set<E> result = EnumSet.noneOf(type);
        for (String value : values) {
            result.add(enumValue(value, type, name));
        }
        return result;
    }

    private <E extends Enum<E>> E enumValue(String value, Class<E> type, String name) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new HarnessCatalogException("CATALOG_MANIFEST_INVALID",
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
            throw new HarnessCatalogException("CATALOG_MANIFEST_INVALID",
                    "MCP " + field + " must be a positive integer", ex);
        }
    }
}
