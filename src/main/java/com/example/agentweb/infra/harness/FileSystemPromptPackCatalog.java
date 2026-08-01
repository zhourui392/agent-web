package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.RuleCatalog;
import com.example.agentweb.domain.capability.RuleDefinition;
import com.example.agentweb.domain.capability.RuleResource;
import com.example.agentweb.domain.harness.HarnessCatalogException;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.PromptPack;
import com.example.agentweb.domain.harness.PromptPackCatalog;
import com.example.agentweb.domain.harness.PromptPackManifest;
import com.example.agentweb.domain.harness.PromptPackResource;
import com.example.agentweb.domain.harness.PromptResourceRole;
import com.example.agentweb.infra.capability.FileSystemRuleCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 把公共 Rule Catalog 映射回 Harness 四资源 Prompt Pack 契约的兼容 Adapter。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class FileSystemPromptPackCatalog implements PromptPackCatalog {

    private final RuleCatalog ruleCatalog;

    @Autowired
    public FileSystemPromptPackCatalog(RuleCatalog ruleCatalog) {
        this.ruleCatalog = ruleCatalog;
    }

    FileSystemPromptPackCatalog(Path root) {
        this(new FileSystemRuleCatalog(root));
    }

    @Override
    public PromptPack resolve(HarnessStage stage) {
        if (stage == null) {
            throw new IllegalArgumentException("prompt pack stage must not be null");
        }
        try {
            return toPromptPack(stage, ruleCatalog.resolve(stage.name()));
        } catch (CapabilityCatalogException ex) {
            throw translate(ex);
        } catch (IllegalArgumentException ex) {
            throw new HarnessCatalogException("CATALOG_MANIFEST_INVALID",
                    "rule definition does not satisfy the Harness prompt pack contract", ex);
        }
    }

    private PromptPack toPromptPack(HarnessStage stage, RuleDefinition definition) {
        Map<PromptResourceRole, String> paths =
                new EnumMap<PromptResourceRole, String>(PromptResourceRole.class);
        List<PromptPackResource> resources = new ArrayList<PromptPackResource>();
        add(resources, paths, definition, PromptResourceRole.SYSTEM, "system");
        add(resources, paths, definition, PromptResourceRole.TASK, "task");
        add(resources, paths, definition, PromptResourceRole.OUTPUT_CONTRACT, "outputContract");
        add(resources, paths, definition, PromptResourceRole.GATE_HINTS, "gateHints");
        PromptPackManifest manifest = new PromptPackManifest(definition.getId(),
                definition.getVersion(), stage, paths);
        return new PromptPack(manifest, resources, definition.getContentHash());
    }

    private void add(List<PromptPackResource> resources,
                     Map<PromptResourceRole, String> paths,
                     RuleDefinition definition, PromptResourceRole role, String resourceName) {
        RuleResource resource = definition.requireResource(resourceName);
        paths.put(role, resource.getPath());
        resources.add(new PromptPackResource(role, resource.getPath(), resource.getContent(),
                resource.getContentHash()));
    }

    private HarnessCatalogException translate(CapabilityCatalogException ex) {
        String code = ex.getCode();
        if ("RULE_DEFINITION_NOT_FOUND".equals(code)) {
            code = "PROMPT_PACK_NOT_FOUND";
        } else if ("RULE_DEFINITION_VERSION_CONFLICT".equals(code)) {
            code = "PROMPT_PACK_VERSION_CONFLICT";
        }
        return new HarnessCatalogException(code, ex.getMessage(), ex);
    }
}
