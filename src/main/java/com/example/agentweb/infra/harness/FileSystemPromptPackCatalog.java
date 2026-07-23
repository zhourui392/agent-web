package com.example.agentweb.infra.harness;

import com.example.agentweb.config.harness.HarnessCatalogProperties;
import com.example.agentweb.domain.harness.HarnessCatalogException;
import com.example.agentweb.domain.harness.HarnessHashing;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.PromptPack;
import com.example.agentweb.domain.harness.PromptPackCatalog;
import com.example.agentweb.domain.harness.PromptPackManifest;
import com.example.agentweb.domain.harness.PromptPackResource;
import com.example.agentweb.domain.harness.PromptResourceRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 每次解析均从受控目录读取的 Prompt Pack Catalog。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class FileSystemPromptPackCatalog implements PromptPackCatalog {

    private final Path root;

    @Autowired
    public FileSystemPromptPackCatalog(HarnessCatalogProperties properties) {
        this(Paths.get(properties.getPromptPackRoot()));
    }

    FileSystemPromptPackCatalog(Path root) {
        this.root = root;
    }

    @Override
    public PromptPack resolve(HarnessStage stage) {
        if (stage == null) {
            throw new IllegalArgumentException("prompt pack stage must not be null");
        }
        Path realRoot = HarnessCatalogFiles.realRoot(root);
        List<PromptPack> matches = new ArrayList<PromptPack>();
        for (Path manifestPath : HarnessCatalogFiles.manifests(realRoot)) {
            PromptPack pack = parse(realRoot, manifestPath);
            if (pack.getManifest().getStage() == stage) {
                matches.add(pack);
            }
        }
        if (matches.isEmpty()) {
            throw new HarnessCatalogException("PROMPT_PACK_NOT_FOUND",
                    "no prompt pack found for stage: " + stage);
        }
        if (matches.size() > 1) {
            throw new HarnessCatalogException("PROMPT_PACK_VERSION_CONFLICT",
                    "multiple prompt pack versions found for stage: " + stage);
        }
        return matches.get(0);
    }

    private PromptPack parse(Path realRoot, Path manifestPath) {
        HarnessCatalogFiles.CatalogFile manifestFile = HarnessCatalogFiles.readManifest(realRoot, manifestPath);
        CatalogYaml yaml = CatalogYaml.parse(manifestFile.getBytes(), manifestPath.toString());
        requireSchemaVersion(yaml);
        HarnessStage stage = parseStage(yaml.requiredString("stage"));
        Map<String, Object> declared = yaml.requiredMap("resources");
        Map<PromptResourceRole, String> paths = new EnumMap<PromptResourceRole, String>(PromptResourceRole.class);
        paths.put(PromptResourceRole.SYSTEM, CatalogYaml.requiredString(declared, "system"));
        paths.put(PromptResourceRole.TASK, CatalogYaml.requiredString(declared, "task"));
        paths.put(PromptResourceRole.OUTPUT_CONTRACT,
                CatalogYaml.requiredString(declared, "outputContract"));
        paths.put(PromptResourceRole.GATE_HINTS, CatalogYaml.requiredString(declared, "gateHints"));
        PromptPackManifest manifest = new PromptPackManifest(yaml.requiredString("id"),
                yaml.requiredString("version"), stage, paths);

        Path packageDir = manifestPath.getParent();
        List<HarnessCatalogFiles.CatalogFile> packageFiles = new ArrayList<HarnessCatalogFiles.CatalogFile>();
        packageFiles.add(manifestFile);
        List<PromptPackResource> resources = new ArrayList<PromptPackResource>();
        for (PromptResourceRole role : PromptResourceRole.values()) {
            HarnessCatalogFiles.CatalogFile file = HarnessCatalogFiles.readPackageFile(
                    realRoot, packageDir, paths.get(role));
            packageFiles.add(file);
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            resources.add(new PromptPackResource(role, file.getRelativePath(), content,
                    HarnessHashing.sha256(file.getBytes())));
        }
        return new PromptPack(manifest, resources, HarnessCatalogFiles.packageHash(packageFiles));
    }

    private void requireSchemaVersion(CatalogYaml yaml) {
        if (!"1".equals(yaml.requiredString("schemaVersion"))) {
            throw new HarnessCatalogException("CATALOG_SCHEMA_UNSUPPORTED",
                    "unsupported prompt pack schema version");
        }
    }

    private HarnessStage parseStage(String value) {
        try {
            return HarnessStage.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new HarnessCatalogException("CATALOG_MANIFEST_INVALID",
                    "unknown prompt pack stage: " + value, ex);
        }
    }
}
