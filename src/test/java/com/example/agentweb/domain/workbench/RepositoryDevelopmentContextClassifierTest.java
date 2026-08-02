package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository 根级开发 marker 的领域分类规则测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RepositoryDevelopmentContextClassifierTest {

    private final RepositoryDevelopmentContextClassifier classifier =
            new RepositoryDevelopmentContextClassifier();

    @Test
    void shouldClassifyTechnologyBuildToolsAndInstructionReferencesDeterministically() {
        RepositoryDevelopmentContext context = classifier.classify("agent-web",
                EnumSet.of(
                        RepositoryDevelopmentMarker.README_MARKDOWN,
                        RepositoryDevelopmentMarker.BUILD_GRADLE_KOTLIN,
                        RepositoryDevelopmentMarker.POM_XML,
                        RepositoryDevelopmentMarker.DEVELOPMENT_MARKDOWN));

        assertEquals("agent-web", context.getRepositoryKey());
        assertEquals(Collections.singletonList(RepositoryTechnologyType.JAVA),
                context.getTechnologyTypes());
        assertEquals(Arrays.asList(
                        RepositoryBuildTool.MAVEN,
                        RepositoryBuildTool.GRADLE),
                context.getBuildTools());
        assertEquals(Arrays.asList("README.md", "DEVELOPMENT.md"),
                relativePaths(context.getInstructionReferences()));
        assertEquals(Arrays.asList(
                        "pom.xml", "build.gradle.kts", "README.md", "DEVELOPMENT.md"),
                context.getDetectedMarkerPaths());
        assertTrue(context.hasDetectedDevelopmentMetadata());
    }

    @Test
    void shouldDeduplicateFactsWhenMultipleMarkersRepresentTheSameTechnologyOrTool() {
        RepositoryDevelopmentContext context = classifier.classify("service",
                EnumSet.of(
                        RepositoryDevelopmentMarker.BUILD_GRADLE,
                        RepositoryDevelopmentMarker.BUILD_GRADLE_KOTLIN,
                        RepositoryDevelopmentMarker.PYPROJECT_TOML,
                        RepositoryDevelopmentMarker.REQUIREMENTS_TEXT));

        assertEquals(Arrays.asList(
                        RepositoryTechnologyType.JAVA,
                        RepositoryTechnologyType.PYTHON),
                context.getTechnologyTypes());
        assertEquals(Arrays.asList(
                        RepositoryBuildTool.GRADLE,
                        RepositoryBuildTool.PYPROJECT,
                        RepositoryBuildTool.PIP_REQUIREMENTS),
                context.getBuildTools());
    }

    @Test
    void completeMarkerCatalogShouldClassifyEverySupportedTechnologyAndBuildEntry() {
        RepositoryDevelopmentContext context = classifier.classify(
                "polyglot", EnumSet.allOf(RepositoryDevelopmentMarker.class));

        assertEquals(Arrays.asList(
                        RepositoryTechnologyType.JAVA,
                        RepositoryTechnologyType.NODE_JS,
                        RepositoryTechnologyType.PYTHON,
                        RepositoryTechnologyType.GO,
                        RepositoryTechnologyType.RUST),
                context.getTechnologyTypes());
        assertEquals(Arrays.asList(
                        RepositoryBuildTool.MAVEN,
                        RepositoryBuildTool.GRADLE,
                        RepositoryBuildTool.NODE_PACKAGE,
                        RepositoryBuildTool.PYPROJECT,
                        RepositoryBuildTool.PIP_REQUIREMENTS,
                        RepositoryBuildTool.GO_MODULES,
                        RepositoryBuildTool.CARGO),
                context.getBuildTools());
        assertEquals(Arrays.asList(
                        "README.md", "CONTRIBUTING.md", "DEVELOPMENT.md"),
                relativePaths(context.getInstructionReferences()));
    }

    @Test
    void shouldRepresentRepositoryWithoutKnownMarkersWithoutInventingFacts() {
        RepositoryDevelopmentContext context = classifier.classify(
                "unknown-service", Collections.<RepositoryDevelopmentMarker>emptySet());

        assertTrue(context.getTechnologyTypes().isEmpty());
        assertTrue(context.getBuildTools().isEmpty());
        assertTrue(context.getInstructionReferences().isEmpty());
        assertTrue(context.getDetectedMarkerPaths().isEmpty());
        assertFalse(context.hasDetectedDevelopmentMetadata());
    }

    @Test
    void shouldClassifyNaturalSortedMarkerSetsWithoutProbingForNull() {
        SortedSet<RepositoryDevelopmentMarker> markers =
                new TreeSet<RepositoryDevelopmentMarker>();
        markers.add(RepositoryDevelopmentMarker.POM_XML);

        RepositoryDevelopmentContext context = classifier.classify(
                "agent-web", markers);

        assertEquals(Collections.singletonList(
                        RepositoryTechnologyType.JAVA),
                context.getTechnologyTypes());
        assertEquals(Collections.singletonList(RepositoryBuildTool.MAVEN),
                context.getBuildTools());
    }

    @Test
    void supportedMarkerCatalogMustNeverContainCliInstructionFiles() {
        List<String> supportedPaths = Arrays.stream(RepositoryDevelopmentMarker.values())
                .map(RepositoryDevelopmentMarker::getRelativePath)
                .collect(Collectors.toList());

        assertFalse(supportedPaths.contains("AGENTS.md"));
        assertFalse(supportedPaths.contains("CLAUDE.md"));
        assertEquals(supportedPaths.size(), supportedPaths.stream().distinct().count());
    }

    @Test
    void shouldRejectUntrustedClassificationInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> classifier.classify("../outside",
                        Collections.<RepositoryDevelopmentMarker>emptySet()));
        assertThrows(IllegalArgumentException.class,
                () -> classifier.classify("agent-web", null));
        assertThrows(IllegalArgumentException.class,
                () -> classifier.classify("agent-web",
                        Collections.singleton(null)));
    }

    private List<String> relativePaths(List<RepositoryInstructionReference> references) {
        return references.stream()
                .map(RepositoryInstructionReference::getRelativePath)
                .collect(Collectors.toList());
    }
}
