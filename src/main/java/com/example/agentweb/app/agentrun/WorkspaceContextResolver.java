package com.example.agentweb.app.agentrun;

import com.example.agentweb.domain.refinery.TrustTier;
import com.example.agentweb.infra.FsProperties;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves lightweight workspace context from workingDir.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Component
@Slf4j
public class WorkspaceContextResolver {

    private static final List<String> DEFAULT_INDEX_PATHS = Arrays.asList(
            "docs/issue-log/INDEX.md",
            "docs/known-issues/INDEX.md",
            "docs/playbooks/INDEX.md"
    );
    private static final String MANIFEST_FILE = ".agent-web.yml";

    private final List<Path> allowedRoots;

    public WorkspaceContextResolver(FsProperties fsProperties) {
        this.allowedRoots = normalizeRoots(fsProperties == null ? null : fsProperties.getRoots());
    }

    public WorkspaceContext resolve(String workingDir) {
        Path work = normalizeWorkingDir(workingDir);
        Path ceiling = nearestAllowedRoot(work);
        if (!allowedRoots.isEmpty() && ceiling == null) {
            return new WorkspaceContext(work, work, null, null,
                    new ArrayList<WorkspaceKnowledgeIndex>(), new LinkedHashMap<String, WorkspaceGuardrail>());
        }
        Path workspaceRoot = discoverWorkspaceRoot(work, ceiling);
        Manifest manifest = readManifest(workspaceRoot);
        List<WorkspaceKnowledgeIndex> indexes = discoverIndexes(workspaceRoot, manifest);
        return new WorkspaceContext(work, workspaceRoot, manifest.name, manifest.description,
                indexes, manifest.guardrails, parseTier(manifest.recallMinTier));
    }

    private List<Path> normalizeRoots(List<String> roots) {
        List<Path> normalized = new ArrayList<Path>();
        if (roots == null) {
            return normalized;
        }
        for (String root : roots) {
            if (root == null || root.trim().isEmpty()) {
                continue;
            }
            normalized.add(Paths.get(root).toAbsolutePath().normalize());
        }
        return normalized;
    }

    private Path normalizeWorkingDir(String workingDir) {
        if (workingDir == null || workingDir.trim().isEmpty()) {
            return Paths.get(".").toAbsolutePath().normalize();
        }
        return Paths.get(workingDir).toAbsolutePath().normalize();
    }

    private Path nearestAllowedRoot(Path workingDir) {
        Path nearest = null;
        for (Path root : allowedRoots) {
            if (!workingDir.startsWith(root)) {
                continue;
            }
            if (nearest == null || root.getNameCount() > nearest.getNameCount()) {
                nearest = root;
            }
        }
        return nearest;
    }

    private Path discoverWorkspaceRoot(Path workingDir, Path ceiling) {
        if (ceiling == null) {
            return workingDir;
        }
        Path cursor = workingDir;
        while (cursor != null && cursor.startsWith(ceiling)) {
            if (hasContextMarker(cursor)) {
                return cursor;
            }
            if (cursor.equals(ceiling)) {
                break;
            }
            cursor = cursor.getParent();
        }
        return ceiling;
    }

    private boolean hasContextMarker(Path dir) {
        if (Files.isRegularFile(dir.resolve(MANIFEST_FILE))) {
            return true;
        }
        for (String path : DEFAULT_INDEX_PATHS) {
            if (Files.isRegularFile(dir.resolve(path))) {
                return true;
            }
        }
        return false;
    }

    private List<WorkspaceKnowledgeIndex> discoverIndexes(Path root, Manifest manifest) {
        Map<String, WorkspaceKnowledgeIndex> indexes = new LinkedHashMap<String, WorkspaceKnowledgeIndex>();
        for (String rel : DEFAULT_INDEX_PATHS) {
            addIndexIfExists(indexes, root, nameOf(rel), rel, 0, WorkspaceKnowledgeIndex.Mode.POINTER);
        }
        for (ManifestIndex index : manifest.indexes) {
            addIndexIfExists(indexes, root, index.name, index.path, index.topK, parseMode(index.mode));
        }
        return new ArrayList<WorkspaceKnowledgeIndex>(indexes.values());
    }

    private TrustTier parseTier(String tier) {
        if (tier == null || tier.trim().isEmpty()) {
            return null;
        }
        try {
            return TrustTier.valueOf(tier.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("workspace-manifest-invalid-min-tier value={}", tier);
            return null;
        }
    }

    private WorkspaceKnowledgeIndex.Mode parseMode(String mode) {
        return "inline".equalsIgnoreCase(mode == null ? null : mode.trim())
                ? WorkspaceKnowledgeIndex.Mode.INLINE
                : WorkspaceKnowledgeIndex.Mode.POINTER;
    }

    private void addIndexIfExists(Map<String, WorkspaceKnowledgeIndex> indexes,
                                  Path root,
                                  String name,
                                  String relativePath,
                                  int topK,
                                  WorkspaceKnowledgeIndex.Mode mode) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return;
        }
        Path path = root.resolve(relativePath.trim()).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return;
        }
        String normalizedRel = root.relativize(path).toString().replace('\\', '/');
        indexes.put(normalizedRel, new WorkspaceKnowledgeIndex(
                name == null || name.trim().isEmpty() ? nameOf(normalizedRel) : name.trim(),
                path,
                normalizedRel,
                topK,
                mode));
    }

    private String nameOf(String rel) {
        if (rel == null) {
            return "index";
        }
        if (rel.contains("issue-log")) {
            return "issue-log";
        }
        if (rel.contains("known-issues")) {
            return "known-issues";
        }
        if (rel.contains("playbooks")) {
            return "playbooks";
        }
        return "index";
    }

    private Manifest readManifest(Path root) {
        Path manifestPath = root.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath)) {
            return new Manifest();
        }
        try {
            return Manifest.parse(new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.warn("workspace-manifest-read-failed path={} reason={}", manifestPath, e.getMessage());
            return new Manifest();
        }
    }

    private static final class Manifest {
        String name;
        String description;
        String recallMinTier;
        final List<ManifestIndex> indexes = new ArrayList<ManifestIndex>();
        final Map<String, WorkspaceGuardrail> guardrails = new LinkedHashMap<String, WorkspaceGuardrail>();

        static Manifest parse(String content) {
            Manifest manifest = new Manifest();
            Object loaded = new Yaml().load(content == null ? "" : content);
            if (!(loaded instanceof Map)) {
                return manifest;
            }
            Map<?, ?> root = (Map<?, ?>) loaded;
            manifest.name = asString(root.get("name"));
            manifest.description = asString(root.get("description"));
            parseIndexes(manifest, root.get("knowledge_indexes"));
            parseGuardrails(manifest, root.get("guardrails"));
            parseRecall(manifest, root.get("recall"));
            return manifest;
        }

        private static void parseIndexes(Manifest manifest, Object value) {
            if (!(value instanceof List)) {
                return;
            }
            for (Object item : (List<?>) value) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> itemMap = (Map<?, ?>) item;
                ManifestIndex index = new ManifestIndex();
                index.name = asString(itemMap.get("name"));
                index.path = asString(itemMap.get("path"));
                index.topK = asInt(itemMap.get("top_k"), 0);
                index.mode = asString(itemMap.get("mode"));
                manifest.indexes.add(index);
            }
        }

        private static void parseRecall(Manifest manifest, Object value) {
            if (!(value instanceof Map)) {
                return;
            }
            manifest.recallMinTier = asString(((Map<?, ?>) value).get("min_tier"));
        }

        private static void parseGuardrails(Manifest manifest, Object value) {
            if (!(value instanceof Map)) {
                return;
            }
            Map<?, ?> guardrailMap = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : guardrailMap.entrySet()) {
                String env = asString(entry.getKey());
                if (env == null || !(entry.getValue() instanceof Map)) {
                    continue;
                }
                Map<?, ?> detail = (Map<?, ?>) entry.getValue();
                manifest.guardrails.put(env, new WorkspaceGuardrail(
                        asBoolean(detail.get("readonly")),
                        asString(detail.get("prompt"))));
            }
        }

        private static String asString(Object value) {
            if (value == null) {
                return null;
            }
            return String.valueOf(value);
        }

        private static int asInt(Object value, int fallback) {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static boolean asBoolean(Object value) {
            if (value instanceof Boolean) {
                return ((Boolean) value).booleanValue();
            }
            return value != null && Boolean.parseBoolean(String.valueOf(value));
        }
    }

    private static final class ManifestIndex {
        String name;
        String path;
        int topK;
        String mode;
    }

}
