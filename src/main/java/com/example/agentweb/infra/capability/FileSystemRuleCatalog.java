package com.example.agentweb.infra.capability;

import com.example.agentweb.config.capability.CapabilityCatalogProperties;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.RuleCatalog;
import com.example.agentweb.domain.capability.RuleDefinition;
import com.example.agentweb.domain.capability.RuleResource;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从可信文件根热读取、且不携带阶段策略的 Rule Catalog。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class FileSystemRuleCatalog implements RuleCatalog {

    private final Path root;

    @Autowired
    public FileSystemRuleCatalog(CapabilityCatalogProperties properties) {
        this(Paths.get(properties.getRuleRoot()));
    }

    public FileSystemRuleCatalog(Path root) {
        this.root = root;
    }

    @Override
    public RuleDefinition resolve(String useCase) {
        String normalized = requireUseCase(useCase);
        List<RuleDefinition> matches = new ArrayList<RuleDefinition>();
        for (RuleDefinition definition : loadDefinitions()) {
            if (definition.supports(normalized)) {
                matches.add(definition);
            }
        }
        if (matches.isEmpty()) {
            throw new CapabilityCatalogException("RULE_DEFINITION_NOT_FOUND",
                    "no rule definition found for use case: " + normalized);
        }
        if (matches.size() > 1) {
            throw new CapabilityCatalogException("RULE_DEFINITION_VERSION_CONFLICT",
                    "multiple rule definition versions found for use case: " + normalized);
        }
        return matches.get(0);
    }

    @Override
    public RuleDefinition resolveById(String logicalId) {
        String requiredId = requireLogicalId(logicalId);
        List<RuleDefinition> matches = new ArrayList<RuleDefinition>();
        for (RuleDefinition definition : loadDefinitions()) {
            if (requiredId.equals(definition.getId())) {
                matches.add(definition);
            }
        }
        if (matches.isEmpty()) {
            throw new CapabilityCatalogException("RULE_DEFINITION_NOT_FOUND",
                    "no rule definition found for logical id: " + requiredId);
        }
        if (matches.size() > 1) {
            throw new CapabilityCatalogException("RULE_DEFINITION_VERSION_CONFLICT",
                    "multiple rule definition versions found for logical id: " + requiredId);
        }
        return matches.get(0);
    }

    private List<RuleDefinition> loadDefinitions() {
        Path realRoot = CapabilityCatalogFiles.realRoot(root);
        List<RuleDefinition> definitions = new ArrayList<RuleDefinition>();
        for (Path manifestPath : CapabilityCatalogFiles.manifests(realRoot)) {
            definitions.add(parse(realRoot, manifestPath));
        }
        return definitions;
    }

    private RuleDefinition parse(Path realRoot, Path manifestPath) {
        CapabilityCatalogFiles.CatalogFile manifestFile =
                CapabilityCatalogFiles.readManifest(realRoot, manifestPath);
        CatalogYaml yaml = CatalogYaml.parse(manifestFile.getBytes(), manifestPath.toString());
        requireSchemaVersion(yaml);
        yaml.requireOnlyKeys("schemaVersion", "id", "version", "source", "mandatory",
                "summary", "applicableUseCases", "resources");
        String id = requireLogicalId(yaml.requiredString("id"));
        String source = yaml.requiredString("source");
        boolean mandatory = yaml.requiredBoolean("mandatory");
        String summary = yaml.requiredString("summary");
        Set<String> useCases = requireUseCases(yaml.stringList("applicableUseCases"));
        Map<String, Object> declared = yaml.requiredMap("resources");
        Path packageDir = manifestPath.getParent();
        List<CapabilityCatalogFiles.CatalogFile> packageFiles =
                new ArrayList<CapabilityCatalogFiles.CatalogFile>();
        packageFiles.add(manifestFile);
        List<RuleResource> resources = new ArrayList<RuleResource>();
        for (Map.Entry<String, Object> entry : declared.entrySet()) {
            String relativePath = requiredResourcePath(entry);
            CapabilityCatalogFiles.CatalogFile file = CapabilityCatalogFiles.readPackageFile(
                    realRoot, packageDir, relativePath);
            packageFiles.add(file);
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            resources.add(new RuleResource(entry.getKey(), file.getRelativePath(), content,
                    CanonicalHashing.sha256(file.getBytes())));
        }
        return new RuleDefinition(id, yaml.requiredString("version"), source, mandatory, summary,
                useCases, resources, CapabilityCatalogFiles.packageHash(packageFiles));
    }

    private Set<String> requireUseCases(List<String> values) {
        if (values.isEmpty()) {
            throw new CapabilityCatalogException("CATALOG_MANIFEST_INVALID",
                    "manifest field must not be empty: applicableUseCases");
        }
        Set<String> result = new LinkedHashSet<String>();
        for (String value : values) {
            result.add(requireUseCase(value));
        }
        return result;
    }

    private String requiredResourcePath(Map.Entry<String, Object> entry) {
        if (!StringUtils.hasText(entry.getKey()) || entry.getValue() == null
                || !StringUtils.hasText(String.valueOf(entry.getValue()))) {
            throw new CapabilityCatalogException("CATALOG_MANIFEST_INVALID",
                    "rule resource name and path must not be blank");
        }
        return String.valueOf(entry.getValue()).trim();
    }

    private void requireSchemaVersion(CatalogYaml yaml) {
        if (!"1".equals(yaml.requiredString("schemaVersion"))) {
            throw new CapabilityCatalogException("CATALOG_SCHEMA_UNSUPPORTED",
                    "unsupported rule catalog schema version");
        }
    }

    private String requireUseCase(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("rule use case must not be blank");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String requireLogicalId(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("rule logical id must not be blank");
        }
        return value.trim();
    }

}
