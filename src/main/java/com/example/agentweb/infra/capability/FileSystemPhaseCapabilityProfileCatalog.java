package com.example.agentweb.infra.capability;

import com.example.agentweb.config.workbench.WorkbenchCapabilityProperties;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileCatalog;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 受控文件根中的四阶段版本化 Phase Capability Profile Catalog。
 *
 * <p>每次读取均重新扫描并校验完整四阶段集合；Catalog 不使用持久化 Override
 * 或用户输入证明 allowlist。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class FileSystemPhaseCapabilityProfileCatalog
        implements PhaseCapabilityProfileCatalog {

    private static final String SUPPORTED_SCHEMA =
            "workbench-phase-profile@1";
    private static final Set<String> REFERENCE_FIELDS = referenceFields();

    private final Path root;

    @Autowired
    public FileSystemPhaseCapabilityProfileCatalog(
            WorkbenchCapabilityProperties properties) {
        this(Paths.get(properties.getProfileRoot()));
    }

    public FileSystemPhaseCapabilityProfileCatalog(Path root) {
        this.root = root;
    }

    @Override
    public PhaseCapabilityProfile requireProfile(WorkbenchPhase phase) {
        if (phase == null) {
            throw new IllegalArgumentException(
                    "workbench phase must not be null");
        }
        Map<WorkbenchPhase, PhaseCapabilityProfile> profiles = loadProfiles();
        return profiles.get(phase);
    }

    private Map<WorkbenchPhase, PhaseCapabilityProfile> loadProfiles() {
        Path realRoot = CapabilityCatalogFiles.realRoot(root);
        Map<WorkbenchPhase, PhaseCapabilityProfile> profiles =
                new EnumMap<WorkbenchPhase, PhaseCapabilityProfile>(
                        WorkbenchPhase.class);
        for (Path manifest : CapabilityCatalogFiles.manifests(realRoot)) {
            PhaseCapabilityProfile profile = parse(realRoot, manifest);
            if (profiles.put(profile.getPhase(), profile) != null) {
                throw failure(
                        "PHASE_PROFILE_VERSION_CONFLICT",
                        "more than one profile is configured for a workbench phase");
            }
        }
        if (profiles.size() != WorkbenchPhase.values().length) {
            throw failure(
                    "PHASE_PROFILE_SET_INCOMPLETE",
                    "the four fixed workbench phase profiles are required");
        }
        return profiles;
    }

    private PhaseCapabilityProfile parse(Path realRoot, Path manifestPath) {
        CapabilityCatalogFiles.CatalogFile manifest =
                CapabilityCatalogFiles.readManifest(realRoot, manifestPath);
        CatalogYaml yaml = CatalogYaml.parse(
                manifest.getBytes(), manifestPath.toString());
        yaml.requireOnlyKeys(
                "schema", "id", "version", "phase",
                "rules", "skills", "mcp_servers");
        requireSchema(yaml);
        try {
            WorkbenchPhase phase = WorkbenchPhase.valueOf(
                    yaml.requiredString("phase").toUpperCase(Locale.ROOT));
            String profileId = requireLogicalId(
                    yaml.requiredString("id"), "profile id");
            String version = requireLogicalId(
                    yaml.requiredString("version"), "profile version");
            requireDirectoryIdentity(
                    realRoot, manifestPath, phase, profileId, version);
            List<PhaseCapabilityReference> references =
                    new ArrayList<PhaseCapabilityReference>();
            addReferences(
                    references, yaml.mapList("rules"), PhaseCapabilityType.RULE);
            addReferences(
                    references, yaml.mapList("skills"), PhaseCapabilityType.SKILL);
            addReferences(
                    references, yaml.mapList("mcp_servers"),
                    PhaseCapabilityType.MCP_SERVER);
            return PhaseCapabilityProfile.create(
                    profileId, version, phase, references);
        } catch (CapabilityCatalogException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new CapabilityCatalogException(
                    "CATALOG_MANIFEST_INVALID",
                    "phase capability profile manifest is invalid", ex);
        }
    }

    private void requireSchema(CatalogYaml yaml) {
        if (!SUPPORTED_SCHEMA.equals(yaml.requiredString("schema"))) {
            throw failure(
                    "CATALOG_SCHEMA_UNSUPPORTED",
                    "unsupported phase capability profile schema");
        }
    }

    private void addReferences(
            List<PhaseCapabilityReference> target,
            List<Map<String, Object>> declarations,
            PhaseCapabilityType type) {
        for (Map<String, Object> declaration : declarations) {
            requireOnlyReferenceFields(declaration);
            String id = requireLogicalId(
                    CatalogYaml.requiredString(declaration, "id"),
                    "capability id");
            target.add(new PhaseCapabilityReference(
                    id, type, requiredBoolean(declaration, "required")));
        }
    }

    private void requireOnlyReferenceFields(Map<String, Object> declaration) {
        for (String field : declaration.keySet()) {
            if (!REFERENCE_FIELDS.contains(field)) {
                throw failure(
                        "CATALOG_MANIFEST_INVALID",
                        "phase capability reference contains unsupported field");
            }
        }
    }

    private boolean requiredBoolean(
            Map<String, Object> declaration, String field) {
        Object value = declaration.get(field);
        if (!(value instanceof Boolean)) {
            throw failure(
                    "CATALOG_MANIFEST_INVALID",
                    "phase capability required flag must be boolean");
        }
        return ((Boolean) value).booleanValue();
    }

    private void requireDirectoryIdentity(
            Path realRoot, Path manifestPath, WorkbenchPhase phase,
            String profileId, String version) {
        Path normalized = manifestPath.toAbsolutePath().normalize();
        Path relative = realRoot.relativize(normalized);
        String expectedPhaseKey = phaseKey(phase);
        if (relative.getNameCount() != 3
                || !expectedPhaseKey.equals(relative.getName(0).toString())
                || !version.equals(relative.getName(1).toString())
                || !"manifest.yml".equals(relative.getName(2).toString())
                || !("workbench-" + expectedPhaseKey).equals(profileId)) {
            throw failure(
                    "CATALOG_MANIFEST_INVALID",
                    "phase profile directory, id, version and phase must match");
        }
    }

    private String requireLogicalId(String value, String field) {
        try {
            Path path = Paths.get(value);
            if (path.isAbsolute() || isWindowsAbsolute(value)
                    || value.indexOf('\\') >= 0 || containsTraversal(path)) {
                throw failure(
                        "CATALOG_MANIFEST_INVALID",
                        "phase capability " + field + " must be a logical id");
            }
            return value;
        } catch (InvalidPathException ex) {
            throw new CapabilityCatalogException(
                    "CATALOG_MANIFEST_INVALID",
                    "phase capability " + field + " is invalid", ex);
        }
    }

    private boolean containsTraversal(Path path) {
        for (Path element : path) {
            String value = element.toString();
            if (".".equals(value) || "..".equals(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWindowsAbsolute(String value) {
        return value.matches("^[A-Za-z]:[\\\\/].*")
                || value.startsWith("//");
    }

    private String phaseKey(WorkbenchPhase phase) {
        return phase.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static Set<String> referenceFields() {
        Set<String> fields = new HashSet<String>();
        fields.add("id");
        fields.add("required");
        return fields;
    }

    private CapabilityCatalogException failure(String code, String message) {
        return new CapabilityCatalogException(code, message);
    }
}
