package com.example.agentweb.domain.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署执行证据到五类阶段 Artifact 的确定性转换测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class DeploymentArtifactFactoryTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @Test
    void successfulOutcomeShouldProduceGateReadableDeploymentArtifacts() throws Exception {
        DeploymentExecution execution = DeploymentExecution.prepare("deploy-1", "key-1",
                new DeploymentPermit("run-1", 1, hash('a'), baseline()),
                new DeploymentTemplateReference("local-default", "1", hash('b'), true), NOW);
        execution.begin(baseline(), NOW.plusSeconds(1));
        DeploymentOutcome outcome = new DeploymentOutcome(Arrays.asList(
                result(DeploymentStep.BUILD, 2), result(DeploymentStep.DEPLOY, 3),
                result(DeploymentStep.HEALTH_CHECK, 4),
                result(DeploymentStep.ACCEPTANCE, 5)));
        execution.complete(outcome, NOW.plusSeconds(6));

        java.util.List<RuntimeProducedArtifact> artifacts =
                new DeploymentArtifactFactory().create(execution, outcome, reportEvidence());

        Set<ArtifactType> types = artifacts.stream().map(RuntimeProducedArtifact::getArtifactType)
                .collect(Collectors.toSet());
        assertEquals(EnumSet.of(ArtifactType.PREFLIGHT, ArtifactType.BUILD_EVIDENCE,
                ArtifactType.DEPLOYMENT_RECORD, ArtifactType.ACCEPTANCE_RESULT,
                ArtifactType.FINAL_REPORT), types);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode preflight = json(mapper, artifacts, ArtifactType.PREFLIGHT);
        JsonNode build = json(mapper, artifacts, ArtifactType.BUILD_EVIDENCE);
        JsonNode acceptance = json(mapper, artifacts, ArtifactType.ACCEPTANCE_RESULT);
        JsonNode report = json(mapper, artifacts, ArtifactType.FINAL_REPORT);
        assertTrue(preflight.path("gitBaselineMatches").asBoolean());
        assertEquals(0, build.path("exitCode").asInt());
        assertTrue(acceptance.path("healthPassed").asBoolean());
        assertEquals("AC-1", acceptance.path("criteria").get(0).path("id").asText());
        assertTrue(acceptance.path("criteria").get(0).path("passed").asBoolean());
        assertEquals("REQ-1", report.path("traceability").get(0)
                .path("requirementId").asText());
        assertEquals("SOLUTION#domain", report.path("traceability").get(0)
                .path("designRef").asText());
        assertEquals("RuleTest", report.path("traceability").get(0)
                .path("testRef").asText());
        assertEquals("src/main/A.java", report.path("traceability").get(0)
                .path("implementationRef").asText());
        assertEquals("deploy-1", report.path("traceability").get(0)
                .path("deploymentExecutionId").asText());
    }

    @Test
    void incompleteTraceabilityShouldFailClosed() {
        DeploymentExecution execution = DeploymentExecution.prepare("deploy-1", "key-1",
                new DeploymentPermit("run-1", 1, hash('a'), baseline()),
                new DeploymentTemplateReference("local-default", "1", hash('b'), true), NOW);
        execution.begin(baseline(), NOW.plusSeconds(1));
        DeploymentOutcome outcome = new DeploymentOutcome(Arrays.asList(
                result(DeploymentStep.BUILD, 2), result(DeploymentStep.DEPLOY, 3),
                result(DeploymentStep.HEALTH_CHECK, 4),
                result(DeploymentStep.ACCEPTANCE, 5)));
        execution.complete(outcome, NOW.plusSeconds(6));

        assertThrows(IllegalArgumentException.class, () ->
                new DeploymentArtifactFactory().create(execution, outcome,
                        Collections.<GateArtifact>emptyList()));
    }

    private JsonNode json(ObjectMapper mapper, java.util.List<RuntimeProducedArtifact> artifacts,
                          ArtifactType type) throws Exception {
        return mapper.readTree(artifacts.stream()
                .filter(item -> item.getArtifactType() == type).findFirst()
                .orElseThrow(AssertionError::new).getContent().copyBytes());
    }

    private DeploymentStepResult result(DeploymentStep step, long offset) {
        return new DeploymentStepResult(step, 0, hash('c'), "ok",
                NOW.plusSeconds(offset), NOW.plusSeconds(offset + 1));
    }

    private java.util.List<GateArtifact> reportEvidence() {
        java.util.List<GateArtifact> artifacts = new ArrayList<GateArtifact>();
        artifacts.add(artifact(HarnessStage.ANALYSIS, ArtifactType.REQUIREMENT,
                "# REQ-1 Deliver Harness M4"));
        artifacts.add(artifact(HarnessStage.ANALYSIS, ArtifactType.ACCEPTANCE_CRITERIA,
                "{\"acceptanceCriteria\":[{\"id\":\"AC-1\",\"requirementId\":\"REQ-1\","
                        + "\"description\":\"local flow succeeds\","
                        + "\"verification\":\"acceptance command\"}]}"));
        artifacts.add(artifact(HarnessStage.DESIGN, ArtifactType.TRACEABILITY,
                "{\"links\":[{\"requirementId\":\"REQ-1\","
                        + "\"acceptanceCriteriaId\":\"AC-1\","
                        + "\"designRef\":\"SOLUTION#domain\",\"testRef\":\"RuleTest\"}]}"));
        artifacts.add(artifact(HarnessStage.IMPLEMENTATION, ArtifactType.TRACEABILITY,
                "{\"links\":[{\"requirementId\":\"REQ-1\","
                        + "\"acceptanceCriteriaId\":\"AC-1\","
                        + "\"designRef\":\"SOLUTION#domain\",\"testRef\":\"RuleTest\","
                        + "\"implementationRef\":\"src/main/A.java\"}]}"));
        artifacts.add(artifact(HarnessStage.IMPLEMENTATION, ArtifactType.TEST_EVIDENCE,
                "{\"commands\":[{\"command\":\"mvn RuleTest\",\"phase\":\"GREEN\","
                        + "\"exitCode\":0,\"runtimeObserved\":true}]}"));
        artifacts.add(artifact(HarnessStage.IMPLEMENTATION, ArtifactType.CHANGED_FILES,
                "{\"files\":[{\"path\":\"src/main/A.java\",\"sensitive\":false}]}"));
        return artifacts;
    }

    private GateArtifact artifact(HarnessStage stage, ArtifactType type, String value) {
        ArtifactContent content = ArtifactContent.from(value.getBytes(StandardCharsets.UTF_8));
        ArtifactDescriptor descriptor = new ArtifactDescriptor(
                stage + "-" + type, type, 1, "run-1", stage, 1,
                value.startsWith("{") ? "application/json" : "text/markdown",
                content.getSizeBytes(), content.getSha256(), ArtifactClassification.INTERNAL,
                "agent", NOW, Collections.<ArtifactReference>emptyList());
        return new GateArtifact(descriptor, content);
    }

    private WorkspaceBaseline baseline() {
        return WorkspaceBaseline.capture("/workspace", "feat/m4",
                "0123456789012345678901234567890123456789", false, hash('d'), NOW);
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
