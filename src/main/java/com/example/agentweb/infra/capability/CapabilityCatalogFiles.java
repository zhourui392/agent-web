package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.shared.CanonicalHashing;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Catalog 文件读取、路径约束和稳定包 Hash 技术实现。
 *
 * @author alex
 * @since 2026-07-23
 */
public final class CapabilityCatalogFiles {

    private CapabilityCatalogFiles() {
    }

    public static Path realRoot(Path root) {
        if (root == null) {
            throw failure("CATALOG_ROOT_INVALID", "catalog root must not be null");
        }
        try {
            Path real = root.toRealPath();
            if (!Files.isDirectory(real)) {
                throw failure("CATALOG_ROOT_INVALID", "catalog root must be a directory: " + root);
            }
            return real;
        } catch (IOException ex) {
            throw new CapabilityCatalogException("CATALOG_ROOT_INVALID",
                    "catalog root is not accessible: " + root, ex);
        }
    }

    public static List<Path> manifests(Path realRoot) {
        try (Stream<Path> stream = Files.walk(realRoot, 3)) {
            List<Path> manifests = new ArrayList<Path>();
            stream.filter(path -> Files.isRegularFile(path)
                            && "manifest.yml".equals(path.getFileName().toString()))
                    .forEach(manifests::add);
            manifests.sort(Comparator.comparing(Path::toString));
            return manifests;
        } catch (IOException ex) {
            throw new CapabilityCatalogException("CATALOG_READ_FAILED",
                    "cannot scan catalog root: " + realRoot, ex);
        }
    }

    public static CatalogFile readManifest(Path root, Path manifest) {
        try {
            Path real = manifest.toRealPath();
            if (!real.startsWith(root) || !Files.isRegularFile(real)) {
                throw failure("CATALOG_PATH_ESCAPE", "manifest escapes catalog root: " + manifest);
            }
            return new CatalogFile("manifest.yml", Files.readAllBytes(real));
        } catch (CapabilityCatalogException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new CapabilityCatalogException("CATALOG_READ_FAILED",
                    "cannot read catalog manifest: " + manifest, ex);
        }
    }

    public static CatalogFile readPackageFile(Path root, Path packageDir, String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw failure("CATALOG_MANIFEST_INVALID", "catalog resource path must not be blank");
        }
        Path relative = java.nio.file.Paths.get(relativePath.trim());
        if (relative.isAbsolute() || isCliNativeInstruction(relative)) {
            throw failure(isCliNativeInstruction(relative)
                            ? "CATALOG_NATIVE_INSTRUCTION_FORBIDDEN" : "CATALOG_PATH_ESCAPE",
                    "catalog resource path is forbidden: " + relativePath);
        }
        Path normalizedPackage = packageDir.toAbsolutePath().normalize();
        Path candidate = normalizedPackage.resolve(relative).normalize();
        if (!candidate.startsWith(normalizedPackage)) {
            throw failure("CATALOG_PATH_ESCAPE", "catalog resource escapes package: " + relativePath);
        }
        try {
            if (!Files.exists(candidate)) {
                throw failure("CATALOG_RESOURCE_MISSING", "catalog resource is missing: " + relativePath);
            }
            Path realPackage = packageDir.toRealPath();
            Path real = candidate.toRealPath();
            if (!real.startsWith(root) || !real.startsWith(realPackage) || !Files.isRegularFile(real)) {
                throw failure("CATALOG_PATH_ESCAPE", "catalog resource escapes package: " + relativePath);
            }
            String portable = normalizedPackage.relativize(candidate).toString().replace('\\', '/');
            return new CatalogFile(portable, Files.readAllBytes(real));
        } catch (CapabilityCatalogException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new CapabilityCatalogException("CATALOG_READ_FAILED",
                    "cannot read catalog resource: " + relativePath, ex);
        }
    }

    public static String packageHash(List<CatalogFile> files) {
        Map<String, String> hashes = new java.util.TreeMap<String, String>();
        for (CatalogFile file : files) {
            String previous = hashes.put(file.getRelativePath(), CanonicalHashing.sha256(file.getBytes()));
            if (previous != null) {
                throw failure("CATALOG_MANIFEST_INVALID",
                        "catalog package declares a resource more than once: " + file.getRelativePath());
            }
        }
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            CanonicalHashing.appendFramed(canonical, "path", entry.getKey());
            CanonicalHashing.appendFramed(canonical, "sha256", entry.getValue());
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    public static Map<String, String> resourceHashes(List<CatalogFile> files) {
        Map<String, String> hashes = new java.util.TreeMap<String, String>();
        for (CatalogFile file : files) {
            hashes.put(file.getRelativePath(), CanonicalHashing.sha256(file.getBytes()));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<String, String>(hashes));
    }

    private static boolean isCliNativeInstruction(Path path) {
        for (Path element : path) {
            String name = element.toString().toUpperCase(Locale.ROOT);
            if ("AGENTS.MD".equals(name) || "CLAUDE.MD".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static CapabilityCatalogException failure(String code, String message) {
        return new CapabilityCatalogException(code, message);
    }

    /**
     * 一次受控读取的包内文件。
     *
     * @author alex
     * @since 2026-07-23
     */
    @Getter
    public static final class CatalogFile {

        private final String relativePath;
        private final byte[] bytes;

        public CatalogFile(String relativePath, byte[] bytes) {
            this.relativePath = relativePath;
            this.bytes = bytes.clone();
        }

        public byte[] getBytes() {
            return bytes.clone();
        }
    }
}
