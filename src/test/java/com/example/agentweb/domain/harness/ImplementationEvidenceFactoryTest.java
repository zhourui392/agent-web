package com.example.agentweb.domain.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实现阶段真实 Git 基线和 Changed Files 替换 Agent 声明的测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class ImplementationEvidenceFactoryTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Test
    void shouldRoundTripBaselineAndReplaceClaimedChangedFiles() throws Exception {
        ImplementationEvidenceFactory factory = new ImplementationEvidenceFactory();
        ChangedFileEvidence unchangedUserChange = file("user-notes.txt", "M", '1', false);
        ChangedFileEvidence implementationTargetBefore = file("src/Main.java", "M", '2', false);
        WorkspaceBaseline baseline = baseline('a', Arrays.asList(
                unchangedUserChange, implementationTargetBefore));
        WorkspaceBaseline current = baseline('b', Arrays.asList(
                unchangedUserChange,
                file("src/Main.java", "M", '3', false),
                file("data/secrets.properties", "??", '4', true)));
        RuntimeArtifactBundle bundle = RuntimeArtifactBundle.create(
                RuntimeArtifactBundle.SCHEMA_VERSION, HarnessStage.IMPLEMENTATION,
                Arrays.asList(
                        artifact("claimed", ArtifactType.CHANGED_FILES),
                        artifact("tests", ArtifactType.TEST_EVIDENCE),
                        artifact("summary", ArtifactType.IMPLEMENTATION_SUMMARY),
                        artifact("trace", ArtifactType.TRACEABILITY)),
                StageContract.mvpDefaults().get(HarnessStage.IMPLEMENTATION.ordinal())
                        .getRequiredOutputArtifacts());

        WorkspaceBaseline restored = factory.readBaseline(factory.baseline(baseline));
        RuntimeArtifactBundle enriched = factory.enrich(bundle,
                new WorkspaceChangeEvidence(baseline, current));

        RuntimeProducedArtifact changed = enriched.getArtifacts().stream()
                .filter(item -> item.getArtifactType() == ArtifactType.CHANGED_FILES)
                .findFirst().orElseThrow(AssertionError::new);
        JsonNode json = new ObjectMapper().readTree(changed.getContent().copyBytes());
        assertEquals(baseline.getDiffHash(), restored.getDiffHash());
        assertEquals(2, restored.getFiles().size());
        assertEquals(current.getDiffHash(), json.path("diffHash").asText());
        assertEquals(2, json.path("baselineFiles").size());
        assertEquals(3, json.path("currentFiles").size());
        assertEquals(2, json.path("introducedFiles").size());
        assertEquals("data/secrets.properties",
                json.path("introducedFiles").get(0).path("path").asText());
        assertEquals("src/Main.java",
                json.path("introducedFiles").get(1).path("path").asText());
        assertEquals(2, json.path("files").size());
        assertTrue(json.path("files").get(0).path("sensitive").asBoolean());
    }

    private RuntimeProducedArtifact artifact(String id, ArtifactType type) {
        return new RuntimeProducedArtifact(id, type, "application/json",
                ArtifactClassification.INTERNAL,
                ArtifactContent.from("{}".getBytes(StandardCharsets.UTF_8)));
    }

    private WorkspaceBaseline baseline(char diff, java.util.List<ChangedFileEvidence> files) {
        return WorkspaceBaseline.capture("/workspace", "feat/m4",
                "0123456789012345678901234567890123456789", false, hash(diff), files, NOW);
    }

    private ChangedFileEvidence file(String path, String status, char fingerprint,
                                     boolean sensitive) {
        return new ChangedFileEvidence(path, status, hash(fingerprint), sensitive);
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
