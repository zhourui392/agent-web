package com.example.agentweb.domain.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 部署执行结果到 Stage Artifact 的确定性领域转换。
 *
 * @author alex
 * @since 2026-07-23
 */
public final class DeploymentArtifactFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern REQUIREMENT_ID = Pattern.compile("\\bREQ-[A-Za-z0-9_-]+\\b");

    public List<RuntimeProducedArtifact> create(DeploymentExecution execution,
                                                DeploymentOutcome outcome,
                                                List<GateArtifact> reportEvidence) {
        if (execution == null || outcome == null || !execution.getStatus().isTerminal()) {
            throw new IllegalArgumentException("terminal deployment execution and outcome are required");
        }
        ReportContext context = reportContext(reportEvidence);
        List<RuntimeProducedArtifact> artifacts = new ArrayList<RuntimeProducedArtifact>();
        artifacts.add(artifact("deployment-preflight", ArtifactType.PREFLIGHT,
                preflight(execution)));
        artifacts.add(artifact("deployment-build", ArtifactType.BUILD_EVIDENCE,
                stepEvidence(outcome.result(DeploymentStep.BUILD))));
        artifacts.add(artifact("deployment-record", ArtifactType.DEPLOYMENT_RECORD,
                deploymentRecord(execution, outcome)));
        artifacts.add(artifact("deployment-acceptance", ArtifactType.ACCEPTANCE_RESULT,
                acceptance(outcome, context)));
        artifacts.add(artifact("delivery-final-report", ArtifactType.FINAL_REPORT,
                finalReport(execution, outcome, context)));
        return java.util.Collections.unmodifiableList(artifacts);
    }

    private ObjectNode preflight(DeploymentExecution execution) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("gitBaselineMatches", true);
        node.put("repositoryRoot", execution.getWorkspaceBaseline().getRepositoryRoot());
        node.put("branch", execution.getWorkspaceBaseline().getBranch());
        node.put("head", execution.getWorkspaceBaseline().getHead());
        node.put("diffHash", execution.getWorkspaceBaseline().getDiffHash());
        node.put("approvedInputBaselineHash", execution.getApprovedInputBaselineHash());
        return node;
    }

    private ObjectNode stepEvidence(DeploymentStepResult result) {
        ObjectNode node = MAPPER.createObjectNode();
        if (result == null) {
            node.put("exitCode", -1);
            node.put("status", "SKIPPED");
            return node;
        }
        node.put("step", result.getStep().name());
        node.put("exitCode", result.getExitCode());
        node.put("outputSha256", result.getOutputSha256());
        node.put("outputSummary", result.getOutputSummary());
        node.put("startedAt", result.getStartedAt().toString());
        node.put("finishedAt", result.getFinishedAt().toString());
        return node;
    }

    private ObjectNode deploymentRecord(DeploymentExecution execution,
                                        DeploymentOutcome outcome) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("environment", "local");
        node.put("executionId", execution.getExecutionId());
        node.put("templateId", execution.getTemplate().getTemplateId());
        node.put("templateVersion", execution.getTemplate().getVersion());
        node.put("templateHash", execution.getTemplate().getTemplateHash());
        node.put("rollbackConfigured", execution.getTemplate().isRollbackConfigured());
        node.set("deploy", stepEvidence(outcome.result(DeploymentStep.DEPLOY)));
        node.put("status", execution.getStatus().name());
        return node;
    }

    private ObjectNode acceptance(DeploymentOutcome outcome, ReportContext context) {
        ObjectNode node = MAPPER.createObjectNode();
        DeploymentStepResult health = outcome.result(DeploymentStep.HEALTH_CHECK);
        DeploymentStepResult acceptance = outcome.result(DeploymentStep.ACCEPTANCE);
        node.put("healthPassed", health != null && health.passed());
        node.set("health", stepEvidence(health));
        ArrayNode criteria = node.putArray("criteria");
        for (TraceRow row : context.rows) {
            ObjectNode criterion = criteria.addObject();
            criterion.put("id", row.acceptanceCriteriaId);
            criterion.put("requirementId", row.requirementId);
            criterion.put("passed", acceptance != null && acceptance.passed());
            if (acceptance != null) {
                criterion.put("evidenceSha256", acceptance.getOutputSha256());
            }
        }
        return node;
    }

    private ObjectNode finalReport(DeploymentExecution execution,
                                   DeploymentOutcome outcome, ReportContext context) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("runId", execution.getRunId());
        node.put("deploymentExecutionId", execution.getExecutionId());
        node.put("approvedInputBaselineHash", execution.getApprovedInputBaselineHash());
        node.put("workspaceDiffHash", execution.getWorkspaceBaseline().getDiffHash());
        node.put("templateHash", execution.getTemplate().getTemplateHash());
        node.put("status", execution.getStatus().name());
        node.put("successful", outcome.isSuccessful());
        node.put("traceabilityComplete", true);
        ArrayNode traceability = node.putArray("traceability");
        DeploymentStepResult acceptance = outcome.result(DeploymentStep.ACCEPTANCE);
        for (TraceRow row : context.rows) {
            ObjectNode trace = traceability.addObject();
            trace.put("requirementId", row.requirementId);
            trace.put("acceptanceCriteriaId", row.acceptanceCriteriaId);
            trace.put("acceptanceDescription", row.acceptanceDescription);
            trace.put("verification", row.verification);
            trace.put("designRef", row.designRef);
            trace.put("testRef", row.testRef);
            trace.put("implementationRef", row.implementationRef);
            trace.put("testEvidenceSha256", context.testEvidence.getDescriptor().getSha256());
            trace.put("changedFilesSha256", context.changedFiles.getDescriptor().getSha256());
            trace.put("validationEvidenceSha256",
                    acceptance == null ? "" : acceptance.getOutputSha256());
            trace.put("deploymentExecutionId", execution.getExecutionId());
            trace.put("deploymentPassed", acceptance != null && acceptance.passed());
        }
        ArrayNode sourceArtifacts = node.putArray("sourceArtifacts");
        for (GateArtifact source : context.sources) {
            ArtifactDescriptor descriptor = source.getDescriptor();
            ObjectNode reference = sourceArtifacts.addObject();
            reference.put("artifactId", descriptor.getArtifactId());
            reference.put("artifactType", descriptor.getArtifactType().name());
            reference.put("stage", descriptor.getStage().name());
            reference.put("version", descriptor.getVersion());
            reference.put("sha256", descriptor.getSha256());
        }
        ArrayNode steps = node.putArray("steps");
        for (DeploymentStepResult result : outcome.getResults()) {
            steps.add(stepEvidence(result));
        }
        if (outcome.getFailureReason() != null) {
            node.put("failureReason", outcome.getFailureReason());
        }
        return node;
    }

    private ReportContext reportContext(List<GateArtifact> reportEvidence) {
        if (reportEvidence == null || reportEvidence.isEmpty() || reportEvidence.contains(null)) {
            throw new IllegalArgumentException("approved delivery report evidence is required");
        }
        GateArtifact requirement = requiredArtifact(
                reportEvidence, HarnessStage.ANALYSIS, ArtifactType.REQUIREMENT);
        GateArtifact acceptance = requiredArtifact(
                reportEvidence, HarnessStage.ANALYSIS, ArtifactType.ACCEPTANCE_CRITERIA);
        GateArtifact designTrace = requiredArtifact(
                reportEvidence, HarnessStage.DESIGN, ArtifactType.TRACEABILITY);
        GateArtifact implementationTrace = requiredArtifact(
                reportEvidence, HarnessStage.IMPLEMENTATION, ArtifactType.TRACEABILITY);
        GateArtifact testEvidence = requiredArtifact(
                reportEvidence, HarnessStage.IMPLEMENTATION, ArtifactType.TEST_EVIDENCE);
        GateArtifact changedFiles = requiredArtifact(
                reportEvidence, HarnessStage.IMPLEMENTATION, ArtifactType.CHANGED_FILES);
        Set<String> requirementIds = requirementIds(requirement);
        Map<String, JsonNode> designLinks = links(designTrace, false);
        Map<String, JsonNode> implementationLinks = links(implementationTrace, true);
        requireObservedTestEvidence(testEvidence);
        JsonNode criteria = json(acceptance).path("acceptanceCriteria");
        if (!criteria.isArray() || criteria.isEmpty()) {
            throw new IllegalArgumentException("acceptance criteria evidence is incomplete");
        }
        Set<String> coveredRequirements = new HashSet<String>();
        Set<String> criterionIds = new HashSet<String>();
        List<TraceRow> rows = new ArrayList<TraceRow>();
        for (JsonNode criterion : criteria) {
            String criterionId = requiredText(criterion, "id");
            String requirementId = requiredText(criterion, "requirementId");
            if (!criterionIds.add(criterionId) || !requirementIds.contains(requirementId)) {
                throw new IllegalArgumentException(
                        "acceptance criteria ids and requirement references must be valid");
            }
            String key = linkKey(requirementId, criterionId);
            JsonNode design = designLinks.get(key);
            JsonNode implementation = implementationLinks.get(key);
            if (design == null || implementation == null) {
                throw new IllegalArgumentException("delivery traceability link is missing: " + key);
            }
            String designRef = requiredText(design, "designRef");
            String testRef = requiredText(design, "testRef");
            if (!designRef.equals(requiredText(implementation, "designRef"))
                    || !testRef.equals(requiredText(implementation, "testRef"))) {
                throw new IllegalArgumentException(
                        "implementation traceability does not match approved design");
            }
            String implementationRef = requiredText(implementation, "implementationRef");
            requireChangedFile(changedFiles, implementationRef);
            rows.add(new TraceRow(requirementId, criterionId,
                    requiredText(criterion, "description"),
                    requiredText(criterion, "verification"),
                    designRef, testRef, implementationRef));
            coveredRequirements.add(requirementId);
        }
        if (!coveredRequirements.equals(requirementIds)) {
            throw new IllegalArgumentException("every requirement must have acceptance traceability");
        }
        List<GateArtifact> orderedSources = new ArrayList<GateArtifact>(reportEvidence);
        orderedSources.sort(java.util.Comparator
                .comparing((GateArtifact value) -> value.getDescriptor().getStage().ordinal())
                .thenComparing(value -> value.getDescriptor().getArtifactType().name()));
        return new ReportContext(rows, orderedSources, testEvidence, changedFiles);
    }

    private GateArtifact requiredArtifact(List<GateArtifact> artifacts, HarnessStage stage,
                                          ArtifactType type) {
        GateArtifact found = null;
        for (GateArtifact artifact : artifacts) {
            ArtifactDescriptor descriptor = artifact.getDescriptor();
            if (descriptor.getStage() == stage && descriptor.getArtifactType() == type) {
                if (found != null) {
                    throw new IllegalArgumentException(
                            "delivery report evidence contains duplicate artifact type");
                }
                found = artifact;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(
                    "delivery report evidence is missing: " + stage + '/' + type);
        }
        return found;
    }

    private Set<String> requirementIds(GateArtifact requirement) {
        Set<String> ids = new HashSet<String>();
        Matcher matcher = REQUIREMENT_ID.matcher(requirement.text());
        while (matcher.find()) {
            if (!ids.add(matcher.group())) {
                throw new IllegalArgumentException("requirement ids must be unique");
            }
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("delivery report requires requirement ids");
        }
        return ids;
    }

    private Map<String, JsonNode> links(GateArtifact traceability,
                                        boolean implementationRequired) {
        JsonNode links = json(traceability).path("links");
        if (!links.isArray() || links.isEmpty()) {
            throw new IllegalArgumentException("traceability links are required");
        }
        Map<String, JsonNode> values = new LinkedHashMap<String, JsonNode>();
        for (JsonNode link : links) {
            String key = linkKey(requiredText(link, "requirementId"),
                    requiredText(link, "acceptanceCriteriaId"));
            requiredText(link, "designRef");
            requiredText(link, "testRef");
            if (implementationRequired) {
                requiredText(link, "implementationRef");
            }
            if (values.put(key, link) != null) {
                throw new IllegalArgumentException("traceability links must be unique");
            }
        }
        return values;
    }

    private void requireObservedTestEvidence(GateArtifact testEvidence) {
        JsonNode commands = json(testEvidence).path("commands");
        if (!commands.isArray() || commands.isEmpty()) {
            throw new IllegalArgumentException("runtime-observed test evidence is required");
        }
        for (JsonNode command : commands) {
            if (!command.path("runtimeObserved").asBoolean(false)) {
                throw new IllegalArgumentException("test evidence must be runtime-observed");
            }
        }
    }

    private void requireChangedFile(GateArtifact changedFiles, String implementationRef) {
        JsonNode files = json(changedFiles).path("files");
        if (!files.isArray()) {
            throw new IllegalArgumentException("changed files evidence is invalid");
        }
        for (JsonNode file : files) {
            if (implementationRef.equals(file.path("path").asText())) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "implementation reference is absent from changed files: " + implementationRef);
    }

    private JsonNode json(GateArtifact artifact) {
        try {
            JsonNode root = MAPPER.readTree(artifact.getContent().copyBytes());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("delivery report JSON evidence is invalid");
            }
            return root;
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("delivery report JSON evidence is invalid", ex);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
            throw new IllegalArgumentException("delivery report field is invalid: " + field);
        }
        return value.asText();
    }

    private String linkKey(String requirementId, String acceptanceCriteriaId) {
        return requirementId + ':' + acceptanceCriteriaId;
    }

    private RuntimeProducedArtifact artifact(String id, ArtifactType type, ObjectNode content) {
        try {
            byte[] bytes = MAPPER.writeValueAsString(content).getBytes(StandardCharsets.UTF_8);
            return new RuntimeProducedArtifact(id, type, "application/json",
                    ArtifactClassification.INTERNAL, ArtifactContent.from(bytes));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("deployment artifact JSON could not be generated", ex);
        }
    }

    private static final class ReportContext {
        private final List<TraceRow> rows;
        private final List<GateArtifact> sources;
        private final GateArtifact testEvidence;
        private final GateArtifact changedFiles;

        private ReportContext(List<TraceRow> rows, List<GateArtifact> sources,
                              GateArtifact testEvidence, GateArtifact changedFiles) {
            this.rows = Collections.unmodifiableList(new ArrayList<TraceRow>(rows));
            this.sources = Collections.unmodifiableList(new ArrayList<GateArtifact>(sources));
            this.testEvidence = testEvidence;
            this.changedFiles = changedFiles;
        }
    }

    private static final class TraceRow {
        private final String requirementId;
        private final String acceptanceCriteriaId;
        private final String acceptanceDescription;
        private final String verification;
        private final String designRef;
        private final String testRef;
        private final String implementationRef;

        private TraceRow(String requirementId, String acceptanceCriteriaId,
                         String acceptanceDescription, String verification,
                         String designRef, String testRef, String implementationRef) {
            this.requirementId = requirementId;
            this.acceptanceCriteriaId = acceptanceCriteriaId;
            this.acceptanceDescription = acceptanceDescription;
            this.verification = verification;
            this.designRef = designRef;
            this.testRef = testRef;
            this.implementationRef = implementationRef;
        }
    }
}
