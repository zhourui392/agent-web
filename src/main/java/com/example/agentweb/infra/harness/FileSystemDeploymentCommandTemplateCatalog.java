package com.example.agentweb.infra.harness;

import com.example.agentweb.config.harness.HarnessCatalogProperties;
import com.example.agentweb.domain.harness.DeploymentCommand;
import com.example.agentweb.domain.harness.DeploymentCommandTemplate;
import com.example.agentweb.domain.harness.DeploymentCommandTemplateCatalog;
import com.example.agentweb.domain.harness.DeploymentStep;
import com.example.agentweb.domain.harness.HarnessCatalogException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 管理员只读目录中的 local 部署模板 Catalog。
 *
 * @author alex
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class FileSystemDeploymentCommandTemplateCatalog
        implements DeploymentCommandTemplateCatalog {

    private static final String SUPPORTED_SCHEMA_VERSION = "1";

    private final Path root;

    @Autowired
    public FileSystemDeploymentCommandTemplateCatalog(HarnessCatalogProperties properties) {
        this(Paths.get(properties.getDeploymentTemplateRoot()));
    }

    FileSystemDeploymentCommandTemplateCatalog(Path root) {
        this.root = root;
    }

    @Override
    public DeploymentCommandTemplate resolve(String templateId) {
        if (templateId == null || !templateId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new HarnessCatalogException("DEPLOYMENT_TEMPLATE_INVALID",
                    "deployment template id is invalid");
        }
        Path realRoot = HarnessCatalogFiles.realRoot(root);
        DeploymentCommandTemplate found = null;
        for (Path manifestPath : HarnessCatalogFiles.manifests(realRoot)) {
            DeploymentCommandTemplate candidate = parse(realRoot, manifestPath);
            if (templateId.equals(candidate.getTemplateId())) {
                if (found != null) {
                    throw new HarnessCatalogException("DEPLOYMENT_TEMPLATE_DUPLICATE",
                            "deployment template id is duplicated: " + templateId);
                }
                found = candidate;
            }
        }
        if (found == null) {
            throw new HarnessCatalogException("DEPLOYMENT_TEMPLATE_NOT_FOUND",
                    "deployment template is not registered: " + templateId);
        }
        return found;
    }

    private DeploymentCommandTemplate parse(Path realRoot, Path manifestPath) {
        HarnessCatalogFiles.CatalogFile manifest = HarnessCatalogFiles.readManifest(
                realRoot, manifestPath);
        CatalogYaml yaml = CatalogYaml.parse(manifest.getBytes(), manifestPath.toString());
        if (!SUPPORTED_SCHEMA_VERSION.equals(yaml.requiredString("schemaVersion"))) {
            throw new HarnessCatalogException("CATALOG_SCHEMA_UNSUPPORTED",
                    "unsupported deployment template schema version");
        }
        Map<String, Object> values = yaml.requiredMap("commands");
        Map<DeploymentStep, DeploymentCommand> commands =
                new EnumMap<DeploymentStep, DeploymentCommand>(DeploymentStep.class);
        commands.put(DeploymentStep.BUILD, command(DeploymentStep.BUILD, values.get("build")));
        commands.put(DeploymentStep.DEPLOY, command(DeploymentStep.DEPLOY, values.get("deploy")));
        commands.put(DeploymentStep.HEALTH_CHECK,
                command(DeploymentStep.HEALTH_CHECK, values.get("healthCheck")));
        commands.put(DeploymentStep.ACCEPTANCE,
                command(DeploymentStep.ACCEPTANCE, values.get("acceptance")));
        commands.put(DeploymentStep.ROLLBACK,
                command(DeploymentStep.ROLLBACK, values.get("rollback")));
        try {
            return new DeploymentCommandTemplate(yaml.requiredString("id"),
                    yaml.requiredString("version"), yaml.requiredString("environment"), commands);
        } catch (IllegalArgumentException ex) {
            throw new HarnessCatalogException("CATALOG_MANIFEST_INVALID",
                    "deployment template violates the local command contract", ex);
        }
    }

    private DeploymentCommand command(DeploymentStep step, Object raw) {
        if (!(raw instanceof List)) {
            throw new HarnessCatalogException("CATALOG_MANIFEST_INVALID",
                    "deployment command must be a token list: " + step);
        }
        List<String> arguments = new ArrayList<String>();
        for (Object value : (List<?>) raw) {
            if (value == null || String.valueOf(value).trim().isEmpty()) {
                throw new HarnessCatalogException("CATALOG_MANIFEST_INVALID",
                        "deployment command contains a blank token: " + step);
            }
            arguments.add(String.valueOf(value));
        }
        return new DeploymentCommand(step, arguments);
    }
}
