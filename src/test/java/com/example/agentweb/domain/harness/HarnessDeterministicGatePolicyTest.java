package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 确定性 Gate 直接测试，不依赖模型或请求方提交的布尔结果。
 *
 * @author alex
 * @since 2026-07-23
 */
class HarnessDeterministicGatePolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");
    private final HarnessDeterministicGatePolicy policy = new HarnessDeterministicGatePolicy();

    @Test
    void analysis_rules_should_validate_ids_observable_ac_and_blocking_questions() {
        GateEvaluationContext valid = context(HarnessStage.ANALYSIS,
                artifact(ArtifactType.REQUIREMENT, "# REQ-1 Create run"),
                artifact(ArtifactType.ACCEPTANCE_CRITERIA,
                        "{\"acceptanceCriteria\":[{\"id\":\"AC-1\",\"requirementId\":\"REQ-1\","
                                + "\"description\":\"run is created\",\"verification\":\"HTTP 201 and runId\"}]}"),
                artifact(ArtifactType.IMPACT_ANALYSIS, "Affected: harness domain and API"),
                artifact(ArtifactType.OPEN_QUESTIONS, "{\"questions\":[]}"));

        assertTrue(policy.evaluate("required-artifacts-present", valid).isPassed());
        assertTrue(policy.evaluate("artifact-schema-valid", valid).isPassed());
        assertTrue(policy.evaluate("requirement-ids-unique", valid).isPassed());
        assertTrue(policy.evaluate("acceptance-criteria-observable", valid).isPassed());
        assertTrue(policy.evaluate("no-blocking-open-question", valid).isPassed());

        GateEvaluationContext blocked = context(HarnessStage.ANALYSIS,
                artifact(ArtifactType.REQUIREMENT, "# REQ-1 One\n# REQ-1 Duplicate"),
                artifact(ArtifactType.ACCEPTANCE_CRITERIA,
                        "{\"acceptanceCriteria\":[{\"id\":\"AC-1\",\"requirementId\":\"REQ-1\","
                                + "\"description\":\"unclear\",\"verification\":\"\"}]}"),
                artifact(ArtifactType.IMPACT_ANALYSIS, "impact"),
                artifact(ArtifactType.OPEN_QUESTIONS,
                        "{\"questions\":[{\"id\":\"Q-1\",\"blocking\":true,\"question\":\"tenant?\"}]}"));

        assertFalse(policy.evaluate("requirement-ids-unique", blocked).isPassed());
        assertFalse(policy.evaluate("acceptance-criteria-observable", blocked).isPassed());
        assertFalse(policy.evaluate("no-blocking-open-question", blocked).isPassed());
    }

    @Test
    void design_rules_should_require_requirement_design_and_test_traceability() {
        GateEvaluationContext context = context(HarnessStage.DESIGN,
                artifact(ArtifactType.SOLUTION, "Domain: HarnessRun\nApplication: orchestration"),
                artifact(ArtifactType.CHANGE_PLAN,
                        "{\"changes\":[{\"requirementId\":\"REQ-1\",\"path\":\"domain/harness\","
                                + "\"layer\":\"DOMAIN\",\"description\":\"state transition\"}]}"),
                artifact(ArtifactType.TEST_STRATEGY,
                        "{\"tests\":[{\"requirementId\":\"REQ-1\",\"acceptanceCriteriaIds\":[\"AC-1\"],"
                                + "\"layer\":\"DOMAIN\",\"scenario\":\"valid transition\"}]}"),
                artifact(ArtifactType.DEPLOYMENT_PLAN, "Deploy only to local."),
                artifact(ArtifactType.ROLLBACK_PLAN, "Disable agent.harness.enabled and reconcile manually."),
                artifact(ArtifactType.TRACEABILITY,
                        "{\"links\":[{\"requirementId\":\"REQ-1\",\"acceptanceCriteriaId\":\"AC-1\","
                                + "\"designRef\":\"SOLUTION#domain\",\"testRef\":\"TEST_STRATEGY#1\"}]}"));

        assertTrue(policy.evaluate("requirement-design-coverage-complete", context).isPassed());
        assertTrue(policy.evaluate("requirement-test-coverage-complete", context).isPassed());
        assertTrue(policy.evaluate("layering-decision-present", context).isPassed());
        assertTrue(policy.evaluate("rollback-plan-present", context).isPassed());
    }

    @Test
    void implementation_rules_should_require_red_green_tests_diff_traceability_and_safe_paths() {
        GateEvaluationContext context = context(HarnessStage.IMPLEMENTATION,
                artifact(ArtifactType.CHANGED_FILES,
                        "{\"baselineHead\":\"abc123\",\"currentHead\":\"abc123\",\"diffHash\":\""
                                + repeat('a') + "\",\"files\":[{\"path\":\"src/main/A.java\","
                                + "\"change\":\"MODIFIED\",\"sensitive\":false}]}"),
                artifact(ArtifactType.TEST_EVIDENCE,
                        "{\"commands\":[{\"commandId\":\"test.focused\",\"phase\":\"RED\",\"exitCode\":1},"
                                + "{\"commandId\":\"test.focused\",\"phase\":\"GREEN\",\"exitCode\":0}]}"),
                artifact(ArtifactType.IMPLEMENTATION_SUMMARY, "Implemented REQ-1 with domain behavior."),
                artifact(ArtifactType.TRACEABILITY,
                        "{\"links\":[{\"requirementId\":\"REQ-1\",\"acceptanceCriteriaId\":\"AC-1\","
                                + "\"designRef\":\"SOLUTION#domain\",\"testRef\":\"HarnessRunM4Test\","
                                + "\"implementationRef\":\"src/main/A.java\"}]}"));

        assertTrue(policy.evaluate("git-baseline-unchanged-or-explained", context).isPassed());
        assertTrue(policy.evaluate("tdd-evidence-present-for-business-branches", context).isPassed());
        assertTrue(policy.evaluate("focused-tests-passed", context).isPassed());
        assertTrue(policy.evaluate("traceability-complete", context).isPassed());
        assertTrue(policy.evaluate("no-sensitive-file-change", context).isPassed());
    }

    @Test
    void sensitive_file_gate_should_ignore_unchanged_baseline_files_and_reject_introduced_ones() {
        String baseline = "{\"baselineFiles\":[{\"path\":\"data/user-existing.key\","
                + "\"sensitive\":true}],\"files\":[{\"path\":\"src/main/A.java\","
                + "\"sensitive\":false}]}";
        String introduced = "{\"baselineFiles\":[],\"files\":[{\"path\":"
                + "\"data/secrets.properties\",\"sensitive\":true}]}";

        assertTrue(policy.evaluate("no-sensitive-file-change", context(
                HarnessStage.IMPLEMENTATION,
                artifact(ArtifactType.CHANGED_FILES, baseline))).isPassed());
        assertFalse(policy.evaluate("no-sensitive-file-change", context(
                HarnessStage.IMPLEMENTATION,
                artifact(ArtifactType.CHANGED_FILES, introduced))).isPassed());
    }

    @Test
    void deployment_rules_should_require_local_preflight_build_health_and_acceptance() {
        GateEvaluationContext context = context(HarnessStage.DEPLOYMENT,
                artifact(ArtifactType.PREFLIGHT,
                        "{\"environment\":\"local\",\"gitBaselineMatches\":true,\"passed\":true}"),
                artifact(ArtifactType.BUILD_EVIDENCE,
                        "{\"commandId\":\"build.local\",\"exitCode\":0}"),
                artifact(ArtifactType.DEPLOYMENT_RECORD,
                        "{\"templateId\":\"local-default\",\"status\":\"SUCCEEDED\",\"idempotencyKey\":\"deploy-1\"}"),
                artifact(ArtifactType.ACCEPTANCE_RESULT,
                        "{\"healthPassed\":true,\"criteria\":[{\"id\":\"AC-1\",\"passed\":true}]}"),
                artifact(ArtifactType.FINAL_REPORT, "REQ-1 / AC-1 delivered to local."));

        assertTrue(policy.evaluate("approved-git-baseline-matches", context).isPassed());
        assertTrue(policy.evaluate("build-passed", context).isPassed());
        assertTrue(policy.evaluate("local-health-check-passed", context).isPassed());
        assertTrue(policy.evaluate("acceptance-criteria-passed", context).isPassed());
    }

    private GateEvaluationContext context(HarnessStage stage, GateArtifact... artifacts) {
        List<GateArtifact> values = new ArrayList<GateArtifact>();
        java.util.Collections.addAll(values, artifacts);
        return new GateEvaluationContext(stage, StageContract.mvpDefaults().get(stage.ordinal()), values);
    }

    private GateArtifact artifact(ArtifactType type, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ArtifactContent artifactContent = ArtifactContent.from(bytes);
        ArtifactDescriptor descriptor = new ArtifactDescriptor(
                "artifact-" + type, type, 1, "run-1", HarnessStage.ANALYSIS, 1,
                content.trim().startsWith("{") ? "application/json" : "text/markdown",
                artifactContent.getSizeBytes(), artifactContent.getSha256(),
                ArtifactClassification.INTERNAL, "agent", NOW,
                java.util.Collections.<ArtifactReference>emptyList());
        return new GateArtifact(descriptor, artifactContent);
    }

    private String repeat(char value) {
        return String.join("", java.util.Collections.nCopies(64, String.valueOf(value)));
    }
}
