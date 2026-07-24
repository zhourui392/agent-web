package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runtime 结构化 Artifact Bundle 的阶段合同测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class RuntimeArtifactBundleTest {

    @Test
    void shouldAcceptExactlyTheRequiredStageOutputs() {
        RuntimeArtifactBundle bundle = RuntimeArtifactBundle.create(
                RuntimeArtifactBundle.SCHEMA_VERSION, HarnessStage.ANALYSIS,
                Arrays.asList(
                        artifact("requirements", ArtifactType.REQUIREMENT),
                        artifact("acceptance", ArtifactType.ACCEPTANCE_CRITERIA),
                        artifact("impact", ArtifactType.IMPACT_ANALYSIS),
                        artifact("questions", ArtifactType.OPEN_QUESTIONS)),
                EnumSet.of(ArtifactType.REQUIREMENT, ArtifactType.ACCEPTANCE_CRITERIA,
                        ArtifactType.IMPACT_ANALYSIS, ArtifactType.OPEN_QUESTIONS));

        assertEquals(HarnessStage.ANALYSIS, bundle.getStage());
        assertEquals(4, bundle.getArtifacts().size());
    }

    @Test
    void shouldRejectMissingDuplicateOrUnexpectedOutputs() {
        EnumSet<ArtifactType> required = EnumSet.of(
                ArtifactType.REQUIREMENT, ArtifactType.ACCEPTANCE_CRITERIA);

        assertThrows(IllegalArgumentException.class, () -> RuntimeArtifactBundle.create(
                RuntimeArtifactBundle.SCHEMA_VERSION, HarnessStage.ANALYSIS,
                Collections.singletonList(artifact("requirements", ArtifactType.REQUIREMENT)),
                required));
        assertThrows(IllegalArgumentException.class, () -> RuntimeArtifactBundle.create(
                RuntimeArtifactBundle.SCHEMA_VERSION, HarnessStage.ANALYSIS,
                Arrays.asList(artifact("requirements", ArtifactType.REQUIREMENT),
                        artifact("requirements-2", ArtifactType.REQUIREMENT)), required));
        assertThrows(IllegalArgumentException.class, () -> RuntimeArtifactBundle.create(
                RuntimeArtifactBundle.SCHEMA_VERSION, HarnessStage.ANALYSIS,
                Arrays.asList(artifact("requirements", ArtifactType.REQUIREMENT),
                        artifact("solution", ArtifactType.SOLUTION)), required));
    }

    @Test
    void shouldRejectWrongSchemaBlankContentAndDuplicateIds() {
        RuntimeProducedArtifact valid = artifact("requirements", ArtifactType.REQUIREMENT);

        assertThrows(IllegalArgumentException.class, () -> RuntimeArtifactBundle.create(
                "unknown", HarnessStage.ANALYSIS, Collections.singletonList(valid),
                Collections.singleton(ArtifactType.REQUIREMENT)));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeProducedArtifact(
                "empty", ArtifactType.REQUIREMENT, "application/json",
                ArtifactClassification.INTERNAL, ArtifactContent.from(new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> RuntimeArtifactBundle.create(
                RuntimeArtifactBundle.SCHEMA_VERSION, HarnessStage.ANALYSIS,
                Arrays.asList(valid, artifact("requirements", ArtifactType.ACCEPTANCE_CRITERIA)),
                EnumSet.of(ArtifactType.REQUIREMENT, ArtifactType.ACCEPTANCE_CRITERIA)));
    }

    private RuntimeProducedArtifact artifact(String id, ArtifactType type) {
        return new RuntimeProducedArtifact(id, type, "application/json",
                ArtifactClassification.INTERNAL,
                ArtifactContent.from("{\"items\":[{\"id\":\"x\"}]}"
                        .getBytes(StandardCharsets.UTF_8)));
    }
}
