package com.example.agentweb.domain.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实现阶段声明的 TDD phase 必须绑定 Runtime 真实命令与退出码。
 *
 * @author alex
 * @since 2026-07-23
 */
class ImplementationCommandEvidenceFactoryTest {

    private final ImplementationCommandEvidenceFactory factory =
            new ImplementationCommandEvidenceFactory();

    @Test
    void shouldReplaceAgentExitCodesWithMatchedRuntimeCommandObservations() throws Exception {
        RuntimeArtifactBundle enriched = factory.enrich(bundle(1, 0), Arrays.asList(
                observation(1, 1, 'a'), observation(2, 0, 'b')));

        RuntimeProducedArtifact evidence = enriched.getArtifacts().stream()
                .filter(item -> item.getArtifactType() == ArtifactType.TEST_EVIDENCE)
                .findFirst().orElseThrow(AssertionError::new);
        JsonNode commands = new ObjectMapper().readTree(evidence.getContent().copyBytes())
                .path("commands");
        assertEquals(2, commands.size());
        assertEquals("RED", commands.get(0).path("phase").asText());
        assertEquals(1, commands.get(0).path("exitCode").asInt());
        assertEquals("GREEN", commands.get(1).path("phase").asText());
        assertEquals(0, commands.get(1).path("exitCode").asInt());
        assertEquals(hash('b'), commands.get(1).path("outputHash").asText());
        assertTrue(commands.get(1).path("runtimeObserved").asBoolean());
    }

    @Test
    void shouldFailClosedWhenDeclaredExitCodeDoesNotMatchRuntimeObservation() {
        assertThrows(IllegalArgumentException.class, () -> factory.enrich(
                bundle(1, 0), Arrays.asList(
                        observation(1, 0, 'a'), observation(2, 0, 'b'))));
    }

    @Test
    void shouldFailClosedWhenDeclaredCommandWasNotObserved() {
        assertThrows(IllegalArgumentException.class, () -> factory.enrich(
                bundle(1, 0), Collections.singletonList(
                        new RuntimeCommandObservation(1, "mvn unrelated", 1, hash('a')))));
    }

    private RuntimeArtifactBundle bundle(int redExitCode, int greenExitCode) {
        String testEvidence = "{\"commands\":["
                + "{\"command\":\"mvn -q -Dtest=RuleTest test\",\"phase\":\"RED\","
                + "\"exitCode\":" + redExitCode + "},"
                + "{\"command\":\"mvn -q -Dtest=RuleTest test\",\"phase\":\"GREEN\","
                + "\"exitCode\":" + greenExitCode + "}]}";
        return RuntimeArtifactBundle.create(RuntimeArtifactBundle.SCHEMA_VERSION,
                HarnessStage.IMPLEMENTATION, Arrays.asList(
                        artifact("changed", ArtifactType.CHANGED_FILES, "{}"),
                        artifact("tests", ArtifactType.TEST_EVIDENCE, testEvidence),
                        artifact("summary", ArtifactType.IMPLEMENTATION_SUMMARY, "summary"),
                        artifact("trace", ArtifactType.TRACEABILITY, "{}")),
                StageContract.mvpDefaults().get(HarnessStage.IMPLEMENTATION.ordinal())
                        .getRequiredOutputArtifacts());
    }

    private RuntimeProducedArtifact artifact(String id, ArtifactType type, String content) {
        return new RuntimeProducedArtifact(id, type, "application/json",
                ArtifactClassification.INTERNAL,
                ArtifactContent.from(content.getBytes(StandardCharsets.UTF_8)));
    }

    private RuntimeCommandObservation observation(int sequence, int exitCode, char outputHash) {
        return new RuntimeCommandObservation(sequence,
                "mvn -q -Dtest=RuleTest test", exitCode, hash(outputHash));
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
