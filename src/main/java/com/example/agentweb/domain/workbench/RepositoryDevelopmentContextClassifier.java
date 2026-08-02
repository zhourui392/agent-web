package com.example.agentweb.domain.workbench;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 将 Infrastructure 已安全确认存在的根级 marker 分类为仓库开发事实。
 *
 * <p>规则集中在领域表 {@link RepositoryDevelopmentMarker}，Infrastructure 不按技术栈写条件分支。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RepositoryDevelopmentContextClassifier {

    public RepositoryDevelopmentContext classify(
            String repositoryKey, Set<RepositoryDevelopmentMarker> detectedMarkers) {
        if (detectedMarkers == null) {
            throw new IllegalArgumentException(
                    "detected repository markers must not be null or contain null");
        }
        for (RepositoryDevelopmentMarker marker : detectedMarkers) {
            if (marker == null) {
                throw new IllegalArgumentException(
                        "detected repository markers must not be null or contain null");
            }
        }
        EnumSet<RepositoryDevelopmentMarker> orderedMarkers =
                EnumSet.noneOf(RepositoryDevelopmentMarker.class);
        orderedMarkers.addAll(detectedMarkers);
        EnumSet<RepositoryTechnologyType> technologyTypes =
                EnumSet.noneOf(RepositoryTechnologyType.class);
        EnumSet<RepositoryBuildTool> buildTools =
                EnumSet.noneOf(RepositoryBuildTool.class);
        List<RepositoryInstructionReference> instructionReferences =
                new ArrayList<RepositoryInstructionReference>();
        List<String> markerPaths = new ArrayList<String>();

        for (RepositoryDevelopmentMarker marker : orderedMarkers) {
            markerPaths.add(marker.getRelativePath());
            if (marker.getTechnologyType() != null) {
                technologyTypes.add(marker.getTechnologyType());
            }
            if (marker.getBuildTool() != null) {
                buildTools.add(marker.getBuildTool());
            }
            if (marker.isInstructionReference()) {
                instructionReferences.add(
                        RepositoryInstructionReference.fromDetectedMarker(
                                repositoryKey, marker));
            }
        }
        return RepositoryDevelopmentContext.fromClassification(
                repositoryKey,
                new ArrayList<RepositoryTechnologyType>(technologyTypes),
                new ArrayList<RepositoryBuildTool>(buildTools),
                instructionReferences,
                markerPaths);
    }
}
