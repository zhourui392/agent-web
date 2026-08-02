package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workspace.RepositorySelection;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单个授权仓库由有限根级 marker 推导出的安全开发事实。
 *
 * <p>本值对象不包含文件正文、Secret、命令或绝对路径；所有集合均按领域枚举顺序冻结。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RepositoryDevelopmentContext {

    public static final String HASH_SCHEMA = "repository-development-context@1";

    private final String repositoryKey;
    private final List<RepositoryTechnologyType> technologyTypes;
    private final List<RepositoryBuildTool> buildTools;
    private final List<RepositoryInstructionReference> instructionReferences;
    private final List<String> detectedMarkerPaths;
    private final String contextHash;

    private RepositoryDevelopmentContext(
            String repositoryKey,
            List<RepositoryTechnologyType> technologyTypes,
            List<RepositoryBuildTool> buildTools,
            List<RepositoryInstructionReference> instructionReferences,
            List<String> detectedMarkerPaths) {
        this.repositoryKey = normalizeRepositoryKey(repositoryKey);
        this.technologyTypes = immutableCopy(technologyTypes, "repository technology types");
        this.buildTools = immutableCopy(buildTools, "repository build tools");
        this.instructionReferences = immutableCopy(
                instructionReferences, "repository instruction references");
        this.detectedMarkerPaths = immutableCopy(
                detectedMarkerPaths, "repository detected marker paths");
        this.contextHash = computeHash();
    }

    static RepositoryDevelopmentContext fromClassification(
            String repositoryKey,
            List<RepositoryTechnologyType> technologyTypes,
            List<RepositoryBuildTool> buildTools,
            List<RepositoryInstructionReference> instructionReferences,
            List<String> detectedMarkerPaths) {
        return new RepositoryDevelopmentContext(repositoryKey, technologyTypes, buildTools,
                instructionReferences, detectedMarkerPaths);
    }

    public boolean hasDetectedDevelopmentMetadata() {
        return !detectedMarkerPaths.isEmpty();
    }

    private String computeHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", HASH_SCHEMA);
        CanonicalHashing.appendFramed(canonical, "repositoryKey", repositoryKey);
        for (RepositoryTechnologyType technologyType : technologyTypes) {
            CanonicalHashing.appendFramed(canonical, "technologyType", technologyType.name());
        }
        for (RepositoryBuildTool buildTool : buildTools) {
            CanonicalHashing.appendFramed(canonical, "buildTool", buildTool.name());
        }
        for (RepositoryInstructionReference reference : instructionReferences) {
            CanonicalHashing.appendFramed(
                    canonical, "instructionType", reference.getType().name());
            CanonicalHashing.appendFramed(
                    canonical, "instructionPath", reference.getRelativePath());
        }
        for (String markerPath : detectedMarkerPaths) {
            CanonicalHashing.appendFramed(canonical, "detectedMarker", markerPath);
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    private static String normalizeRepositoryKey(String repositoryKey) {
        return RepositorySelection.of(repositoryKey, Collections.singletonList(repositoryKey))
                .getPrimaryRepositoryKey();
    }

    private static <T> List<T> immutableCopy(List<T> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not be null or contain null");
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RepositoryDevelopmentContext)) {
            return false;
        }
        RepositoryDevelopmentContext that = (RepositoryDevelopmentContext) other;
        return repositoryKey.equals(that.repositoryKey)
                && technologyTypes.equals(that.technologyTypes)
                && buildTools.equals(that.buildTools)
                && instructionReferences.equals(that.instructionReferences)
                && detectedMarkerPaths.equals(that.detectedMarkerPaths)
                && contextHash.equals(that.contextHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repositoryKey, technologyTypes, buildTools,
                instructionReferences, detectedMarkerPaths, contextHash);
    }
}
