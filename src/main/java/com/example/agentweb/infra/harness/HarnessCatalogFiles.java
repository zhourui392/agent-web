package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.harness.HarnessCatalogException;
import com.example.agentweb.infra.capability.CapabilityCatalogFiles;
import lombok.Getter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Harness 部署模板在迁移窗口内使用的 Catalog 文件兼容入口。
 *
 * @author alex
 * @since 2026-08-01
 */
final class HarnessCatalogFiles {

    private HarnessCatalogFiles() {
    }

    static Path realRoot(Path root) {
        return translate(() -> CapabilityCatalogFiles.realRoot(root));
    }

    static List<Path> manifests(Path root) {
        return translate(() -> CapabilityCatalogFiles.manifests(root));
    }

    static CatalogFile readManifest(Path root, Path manifest) {
        return translate(() -> new CatalogFile(
                CapabilityCatalogFiles.readManifest(root, manifest)));
    }

    static CatalogFile readPackageFile(Path root, Path packageDir, String relativePath) {
        return translate(() -> new CatalogFile(
                CapabilityCatalogFiles.readPackageFile(root, packageDir, relativePath)));
    }

    static String packageHash(List<CatalogFile> files) {
        return translate(() -> CapabilityCatalogFiles.packageHash(unwrap(files)));
    }

    static Map<String, String> resourceHashes(List<CatalogFile> files) {
        return translate(() -> CapabilityCatalogFiles.resourceHashes(unwrap(files)));
    }

    private static List<CapabilityCatalogFiles.CatalogFile> unwrap(List<CatalogFile> files) {
        List<CapabilityCatalogFiles.CatalogFile> values =
                new ArrayList<CapabilityCatalogFiles.CatalogFile>();
        for (CatalogFile file : files) {
            values.add(file.delegate);
        }
        return values;
    }

    private static <T> T translate(CatalogOperation<T> operation) {
        try {
            return operation.execute();
        } catch (CapabilityCatalogException ex) {
            throw new HarnessCatalogException(ex.getCode(), ex.getMessage(), ex);
        }
    }

    private interface CatalogOperation<T> {
        T execute();
    }

    @Getter
    static final class CatalogFile {

        private final CapabilityCatalogFiles.CatalogFile delegate;

        private CatalogFile(CapabilityCatalogFiles.CatalogFile delegate) {
            this.delegate = delegate;
        }

        String getRelativePath() {
            return delegate.getRelativePath();
        }

        public byte[] getBytes() {
            return delegate.getBytes();
        }
    }
}
