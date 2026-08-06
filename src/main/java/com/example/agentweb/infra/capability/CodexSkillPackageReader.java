package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.capability.SkillTrustSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 将标准 Codex {@code SKILL.md} 目录转换为 Workbench Skill 包。
 *
 * @author alex
 * @since 2026-08-05
 */
final class CodexSkillPackageReader {

    private static final String ENTRY_FILE = "SKILL.md";
    private static final String VERSION_PREFIX = "sha256-";

    private CodexSkillPackageReader() {
    }

    static List<SkillPackage> discover(
            Path realRoot, SkillTrustSource trustSource) {
        List<SkillPackage> packages = new ArrayList<SkillPackage>();
        for (Path entry : entries(realRoot)) {
            if (!Files.isRegularFile(
                    entry.resolveSibling("manifest.yml"), LinkOption.NOFOLLOW_LINKS)) {
                packages.add(read(realRoot, entry, trustSource));
            }
        }
        return Collections.unmodifiableList(packages);
    }

    private static List<Path> entries(Path realRoot) {
        try (Stream<Path> paths = Files.walk(realRoot)) {
            List<Path> entries = new ArrayList<Path>();
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && ENTRY_FILE.equals(path.getFileName().toString()))
                    .forEach(entries::add);
            entries.sort(java.util.Comparator.comparing(Path::toString));
            return entries;
        } catch (IOException failure) {
            throw catalogFailure(
                    "CATALOG_READ_FAILED", "cannot scan Codex Skill root", failure);
        }
    }

    private static SkillPackage read(
            Path realRoot, Path entry, SkillTrustSource trustSource) {
        List<CapabilityCatalogFiles.CatalogFile> files = packageFiles(
                realRoot, entry.getParent());
        CapabilityCatalogFiles.CatalogFile entryFile = requireEntry(files);
        CatalogYaml frontmatter = frontmatter(entryFile.getBytes(), entry.toString());
        String identifier = frontmatter.requiredString("name");
        String description = frontmatter.requiredString("description");
        String packageHash = CapabilityCatalogFiles.packageHash(files);
        Set<String> resourcePaths = resourcePaths(files);
        SkillManifest manifest = new SkillManifest(
                identifier, derivedVersion(packageHash), description,
                Collections.singleton("WORKBENCH_STAGE"),
                Collections.<String>emptySet(), Collections.singleton(identifier),
                ENTRY_FILE, resourcePaths,
                Collections.emptyList(), Collections.<String>emptySet(),
                Collections.singleton("CODEX"), trustSource,
                Collections.emptyList());
        return new SkillPackage(
                manifest, packageHash,
                new String(entryFile.getBytes(), StandardCharsets.UTF_8),
                CapabilityCatalogFiles.resourceHashes(files),
                resourceContents(files));
    }

    private static List<CapabilityCatalogFiles.CatalogFile> packageFiles(
            Path realRoot, Path packageDirectory) {
        try (Stream<Path> paths = Files.walk(packageDirectory)) {
            List<CapabilityCatalogFiles.CatalogFile> files =
                    new ArrayList<CapabilityCatalogFiles.CatalogFile>();
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !isNativeInstruction(packageDirectory.relativize(path)))
                    .forEach(path -> files.add(readFile(
                            realRoot, packageDirectory, path)));
            files.sort(java.util.Comparator.comparing(
                    CapabilityCatalogFiles.CatalogFile::getRelativePath));
            return files;
        } catch (IOException failure) {
            throw catalogFailure(
                    "CATALOG_READ_FAILED", "cannot read Codex Skill package", failure);
        }
    }

    private static CapabilityCatalogFiles.CatalogFile readFile(
            Path realRoot, Path packageDirectory, Path path) {
        try {
            Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realPackage = packageDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(realRoot) || !real.startsWith(realPackage)
                    || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw catalogFailure(
                        "CATALOG_PATH_ESCAPE", "Codex Skill file escapes its package");
            }
            String relative = packageDirectory.relativize(path).normalize()
                    .toString().replace('\\', '/');
            return new CapabilityCatalogFiles.CatalogFile(
                    relative, Files.readAllBytes(real));
        } catch (CapabilityCatalogException failure) {
            throw failure;
        } catch (IOException failure) {
            throw catalogFailure(
                    "CATALOG_READ_FAILED", "cannot read Codex Skill file", failure);
        }
    }

    private static CapabilityCatalogFiles.CatalogFile requireEntry(
            List<CapabilityCatalogFiles.CatalogFile> files) {
        for (CapabilityCatalogFiles.CatalogFile file : files) {
            if (ENTRY_FILE.equals(file.getRelativePath())) {
                return file;
            }
        }
        throw catalogFailure(
                "CATALOG_MANIFEST_INVALID", "Codex Skill entry is missing");
    }

    private static CatalogYaml frontmatter(byte[] bytes, String source) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        int firstLineEnd = content.indexOf('\n');
        if (firstLineEnd < 0
                || !"---".equals(trimLine(content.substring(0, firstLineEnd)))) {
            throw catalogFailure(
                    "CATALOG_MANIFEST_INVALID", "Codex Skill frontmatter is required");
        }
        int lineStart = firstLineEnd + 1;
        while (lineStart < content.length()) {
            int lineEnd = content.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = content.length();
            }
            if ("---".equals(trimLine(content.substring(lineStart, lineEnd)))) {
                return CatalogYaml.parse(
                        content.substring(firstLineEnd + 1, lineStart)
                                .getBytes(StandardCharsets.UTF_8), source);
            }
            lineStart = lineEnd + 1;
        }
        throw catalogFailure(
                "CATALOG_MANIFEST_INVALID", "Codex Skill frontmatter is not closed");
    }

    private static String trimLine(String line) {
        return line.endsWith("\r")
                ? line.substring(0, line.length() - 1).trim() : line.trim();
    }

    private static Set<String> resourcePaths(
            List<CapabilityCatalogFiles.CatalogFile> files) {
        Set<String> paths = new LinkedHashSet<String>();
        for (CapabilityCatalogFiles.CatalogFile file : files) {
            if (!ENTRY_FILE.equals(file.getRelativePath())) {
                paths.add(file.getRelativePath());
            }
        }
        return Collections.unmodifiableSet(paths);
    }

    private static Map<String, byte[]> resourceContents(
            List<CapabilityCatalogFiles.CatalogFile> files) {
        Map<String, byte[]> contents = new LinkedHashMap<String, byte[]>();
        for (CapabilityCatalogFiles.CatalogFile file : files) {
            if (!ENTRY_FILE.equals(file.getRelativePath())) {
                contents.put(file.getRelativePath(), file.getBytes());
            }
        }
        return Collections.unmodifiableMap(contents);
    }

    private static String derivedVersion(String packageHash) {
        return VERSION_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(HexFormat.of().parseHex(packageHash));
    }

    private static boolean isNativeInstruction(Path relativePath) {
        for (Path element : relativePath) {
            String name = element.toString().toUpperCase(Locale.ROOT);
            if ("AGENTS.MD".equals(name) || "CLAUDE.MD".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static CapabilityCatalogException catalogFailure(
            String code, String message) {
        return new CapabilityCatalogException(code, message);
    }

    private static CapabilityCatalogException catalogFailure(
            String code, String message, Throwable cause) {
        return new CapabilityCatalogException(code, message, cause);
    }
}
