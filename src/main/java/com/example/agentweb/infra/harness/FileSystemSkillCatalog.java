package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.config.harness.HarnessCatalogProperties;
import com.example.agentweb.domain.harness.CapabilityAccess;
import com.example.agentweb.domain.harness.CapabilityKind;
import com.example.agentweb.domain.harness.CapabilityRequest;
import com.example.agentweb.domain.harness.HarnessCatalogException;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.SkillCatalog;
import com.example.agentweb.domain.harness.SkillDependency;
import com.example.agentweb.domain.harness.SkillManifest;
import com.example.agentweb.domain.harness.SkillPackage;
import com.example.agentweb.domain.harness.SkillTrustSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按受控信任根热发现 Skill 的文件系统 Catalog。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class FileSystemSkillCatalog implements SkillCatalog {

    private final List<TrustedRoot> roots;

    @Autowired
    public FileSystemSkillCatalog(HarnessCatalogProperties properties) {
        List<TrustedRoot> configured = new ArrayList<TrustedRoot>();
        configured.add(new TrustedRoot(Paths.get(properties.getPlatformSkillRoot()), SkillTrustSource.PLATFORM));
        addOptional(configured, properties.getApprovedUserSkillRoot(), SkillTrustSource.APPROVED_USER);
        addOptional(configured, properties.getWorkspaceSkillRoot(), SkillTrustSource.WORKSPACE);
        this.roots = Collections.unmodifiableList(configured);
    }

    FileSystemSkillCatalog(Path root, SkillTrustSource trustSource) {
        this.roots = Collections.singletonList(new TrustedRoot(root, trustSource));
    }

    @Override
    public List<SkillPackage> discover() {
        List<SkillPackage> packages = new ArrayList<SkillPackage>();
        for (TrustedRoot trustedRoot : roots) {
            Path realRoot = HarnessCatalogFiles.realRoot(trustedRoot.getPath());
            for (Path manifest : HarnessCatalogFiles.manifests(realRoot)) {
                packages.add(parse(realRoot, manifest, trustedRoot.getTrustSource()));
            }
        }
        packages.sort(Comparator.comparing((SkillPackage value) -> value.getManifest().getId())
                .thenComparing(value -> value.getManifest().getVersion()));
        return Collections.unmodifiableList(packages);
    }

    private SkillPackage parse(Path realRoot, Path manifestPath, SkillTrustSource trustedSource) {
        HarnessCatalogFiles.CatalogFile manifestFile = HarnessCatalogFiles.readManifest(realRoot, manifestPath);
        CatalogYaml yaml = CatalogYaml.parse(manifestFile.getBytes(), manifestPath.toString());
        requireSchemaVersion(yaml);
        requireTrustSource(yaml, trustedSource);
        String entryPath = yaml.requiredString("entry");
        Set<String> resourcePaths = new LinkedHashSet<String>(yaml.stringList("resources"));
        SkillManifest manifest = new SkillManifest(
                yaml.requiredString("id"), yaml.requiredString("version"),
                yaml.requiredString("description"), enumSet(yaml.stringList("stages"),
                HarnessStage.class, "stage"), new LinkedHashSet<String>(yaml.stringList("techTags")),
                new LinkedHashSet<String>(yaml.stringList("explicitTriggers")), entryPath, resourcePaths,
                dependencies(yaml.mapList("dependencies")),
                new LinkedHashSet<String>(yaml.stringList("conflicts")),
                enumSet(yaml.stringList("runtimes"), AgentRuntime.class, "runtime"),
                trustedSource, capabilities(yaml.mapList("capabilities")));

        Path packageDir = manifestPath.getParent();
        List<HarnessCatalogFiles.CatalogFile> files = new ArrayList<HarnessCatalogFiles.CatalogFile>();
        files.add(manifestFile);
        HarnessCatalogFiles.CatalogFile entry = HarnessCatalogFiles.readPackageFile(
                realRoot, packageDir, entryPath);
        files.add(entry);
        for (String resourcePath : resourcePaths) {
            files.add(HarnessCatalogFiles.readPackageFile(realRoot, packageDir, resourcePath));
        }
        return new SkillPackage(manifest, HarnessCatalogFiles.packageHash(files),
                new String(entry.getBytes(), StandardCharsets.UTF_8),
                HarnessCatalogFiles.resourceHashes(files));
    }

    private List<SkillDependency> dependencies(List<Map<String, Object>> values) {
        List<SkillDependency> dependencies = new ArrayList<SkillDependency>();
        for (Map<String, Object> value : values) {
            dependencies.add(new SkillDependency(CatalogYaml.requiredString(value, "id"),
                    CatalogYaml.requiredString(value, "version")));
        }
        return dependencies;
    }

    private List<CapabilityRequest> capabilities(List<Map<String, Object>> values) {
        List<CapabilityRequest> requests = new ArrayList<CapabilityRequest>();
        for (Map<String, Object> value : values) {
            CapabilityKind kind = enumValue(CatalogYaml.requiredString(value, "kind"),
                    CapabilityKind.class, "capability kind");
            CapabilityAccess access = enumValue(CatalogYaml.requiredString(value, "access"),
                    CapabilityAccess.class, "capability access");
            requests.add(new CapabilityRequest(kind, access,
                    CatalogYaml.requiredString(value, "resource")));
        }
        return requests;
    }

    private <E extends Enum<E>> Set<E> enumSet(List<String> values, Class<E> type, String name) {
        if (values.isEmpty()) {
            throw new HarnessCatalogException("CATALOG_MANIFEST_INVALID",
                    "skill " + name + " list must not be empty");
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
                    "unknown skill " + name + ": " + value, ex);
        }
    }

    private void requireSchemaVersion(CatalogYaml yaml) {
        if (!"1".equals(yaml.requiredString("schemaVersion"))) {
            throw new HarnessCatalogException("CATALOG_SCHEMA_UNSUPPORTED",
                    "unsupported skill schema version");
        }
    }

    private void requireTrustSource(CatalogYaml yaml, SkillTrustSource trustedSource) {
        String declared = yaml.optionalString("trustSource");
        if (StringUtils.hasText(declared)
                && !trustedSource.name().equals(declared.toUpperCase(Locale.ROOT))) {
            throw new HarnessCatalogException("CATALOG_TRUST_SOURCE_MISMATCH",
                    "skill manifest trust source does not match its configured root");
        }
    }

    private void addOptional(List<TrustedRoot> configured, String path, SkillTrustSource source) {
        if (StringUtils.hasText(path)) {
            configured.add(new TrustedRoot(Paths.get(path), source));
        }
    }

    /**
     * 管理员配置的 Catalog 根及其不可伪造信任来源。
     *
     * @author zhourui(V33215020)
     * @since 2026-07-23
     */
    private static final class TrustedRoot {

        private final Path path;
        private final SkillTrustSource trustSource;

        private TrustedRoot(Path path, SkillTrustSource trustSource) {
            if (path == null || trustSource == null) {
                throw new IllegalArgumentException("trusted skill root and source must not be null");
            }
            this.path = path;
            this.trustSource = trustSource;
        }

        private Path getPath() {
            return path;
        }

        private SkillTrustSource getTrustSource() {
            return trustSource;
        }
    }
}
