package com.example.agentweb.domain.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M4 固定合同的确定性 Gate 策略；所有结果由 Artifact 正文计算，不接受调用方布尔声明。
 *
 * @author alex
 * @since 2026-07-23
 */
public final class HarnessDeterministicGatePolicy {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern REQUIREMENT_ID = Pattern.compile("\\bREQ-[A-Za-z0-9_-]+\\b");
    private static final Set<ArtifactType> JSON_TYPES = jsonTypes();

    public GateDecision evaluate(String rule, GateEvaluationContext context) {
        String normalizedRule = DomainText.require(rule, "deterministic gate rule");
        if (context == null || !context.getContract().getDeterministicGates().contains(normalizedRule)) {
            throw new IllegalArgumentException("gate rule is not part of the stage contract: "
                    + normalizedRule);
        }
        List<String> evidence = evidence(context);
        boolean passed;
        switch (normalizedRule) {
            case "required-artifacts-present":
                passed = context.hasAllRequiredOutputs();
                break;
            case "artifact-schema-valid":
                passed = validArtifactSchemas(context);
                break;
            case "requirement-ids-unique":
                passed = uniqueRequirementIds(context);
                break;
            case "acceptance-criteria-observable":
                passed = observableAcceptanceCriteria(context);
                break;
            case "no-blocking-open-question":
                passed = noBlockingOpenQuestion(context);
                break;
            case "requirement-design-coverage-complete":
                passed = traceLinksContain(context, "designRef");
                break;
            case "requirement-test-coverage-complete":
                passed = traceLinksContain(context, "testRef");
                break;
            case "layering-decision-present":
                passed = layeringDecisionPresent(context);
                break;
            case "rollback-plan-present":
                passed = nonBlank(context, ArtifactType.ROLLBACK_PLAN);
                break;
            case "git-baseline-unchanged-or-explained":
                passed = gitBaselineStable(context);
                break;
            case "tdd-evidence-present-for-business-branches":
                passed = redBeforeGreen(context);
                break;
            case "focused-tests-passed":
                passed = focusedTestsPassed(context);
                break;
            case "traceability-complete":
                passed = implementationTraceabilityComplete(context);
                break;
            case "no-sensitive-file-change":
                passed = noSensitiveFileChange(context);
                break;
            case "approved-git-baseline-matches":
                passed = booleanField(context, ArtifactType.PREFLIGHT, "gitBaselineMatches");
                break;
            case "build-passed":
                passed = integerField(context, ArtifactType.BUILD_EVIDENCE, "exitCode") == 0;
                break;
            case "local-health-check-passed":
                passed = booleanField(context, ArtifactType.ACCEPTANCE_RESULT, "healthPassed");
                break;
            case "acceptance-criteria-passed":
                passed = allAcceptanceCriteriaPassed(context);
                break;
            default:
                throw new IllegalArgumentException("unsupported deterministic gate rule: "
                        + normalizedRule);
        }
        return passed ? GateDecision.pass(evidence)
                : GateDecision.fail("deterministic gate failed: " + normalizedRule, evidence);
    }

    private boolean validArtifactSchemas(GateEvaluationContext context) {
        if (!context.hasAllRequiredOutputs()) {
            return false;
        }
        for (ArtifactType type : context.getContract().getRequiredOutputArtifacts()) {
            GateArtifact artifact = context.artifact(type);
            if (artifact == null || artifact.text().trim().isEmpty()) {
                return false;
            }
            if (JSON_TYPES.contains(type) && json(artifact) == null) {
                return false;
            }
        }
        return true;
    }

    private boolean uniqueRequirementIds(GateEvaluationContext context) {
        GateArtifact requirement = context.artifact(ArtifactType.REQUIREMENT);
        if (requirement == null) {
            return false;
        }
        Matcher matcher = REQUIREMENT_ID.matcher(requirement.text());
        Set<String> ids = new HashSet<String>();
        int count = 0;
        while (matcher.find()) {
            count++;
            if (!ids.add(matcher.group())) {
                return false;
            }
        }
        return count > 0;
    }

    private boolean observableAcceptanceCriteria(GateEvaluationContext context) {
        JsonNode root = json(context.artifact(ArtifactType.ACCEPTANCE_CRITERIA));
        JsonNode criteria = root == null ? null : root.path("acceptanceCriteria");
        if (criteria == null || !criteria.isArray() || criteria.isEmpty()) {
            return false;
        }
        Set<String> ids = new HashSet<String>();
        for (JsonNode criterion : criteria) {
            if (!nonBlank(criterion, "id") || !nonBlank(criterion, "requirementId")
                    || !nonBlank(criterion, "description") || !nonBlank(criterion, "verification")
                    || !ids.add(criterion.path("id").asText())) {
                return false;
            }
        }
        return true;
    }

    private boolean noBlockingOpenQuestion(GateEvaluationContext context) {
        JsonNode root = json(context.artifact(ArtifactType.OPEN_QUESTIONS));
        JsonNode questions = root == null ? null : root.path("questions");
        if (questions == null || !questions.isArray()) {
            return false;
        }
        for (JsonNode question : questions) {
            if (question.path("blocking").asBoolean(false)
                    && !question.path("answered").asBoolean(false)) {
                return false;
            }
        }
        return true;
    }

    private boolean traceLinksContain(GateEvaluationContext context, String field) {
        JsonNode root = json(context.artifact(ArtifactType.TRACEABILITY));
        JsonNode links = root == null ? null : root.path("links");
        if (links == null || !links.isArray() || links.isEmpty()) {
            return false;
        }
        for (JsonNode link : links) {
            if (!nonBlank(link, "requirementId") || !nonBlank(link, "acceptanceCriteriaId")
                    || !nonBlank(link, field)) {
                return false;
            }
        }
        return true;
    }

    private boolean layeringDecisionPresent(GateEvaluationContext context) {
        GateArtifact solution = context.artifact(ArtifactType.SOLUTION);
        JsonNode changePlan = json(context.artifact(ArtifactType.CHANGE_PLAN));
        if (solution == null || changePlan == null) {
            return false;
        }
        String text = solution.text().toLowerCase(Locale.ROOT);
        if (!text.contains("domain") || !text.contains("application")) {
            return false;
        }
        JsonNode changes = changePlan.path("changes");
        if (!changes.isArray() || changes.isEmpty()) {
            return false;
        }
        for (JsonNode change : changes) {
            if (!nonBlank(change, "layer")) {
                return false;
            }
        }
        return true;
    }

    private boolean gitBaselineStable(GateEvaluationContext context) {
        JsonNode changed = json(context.artifact(ArtifactType.CHANGED_FILES));
        if (changed == null || !nonBlank(changed, "baselineHead") || !nonBlank(changed, "currentHead")
                || !nonBlank(changed, "diffHash")) {
            return false;
        }
        return changed.path("baselineHead").asText().equals(changed.path("currentHead").asText())
                || nonBlank(changed, "baselineExplanation");
    }

    private boolean redBeforeGreen(GateEvaluationContext context) {
        JsonNode commands = commands(context);
        if (commands == null) {
            return false;
        }
        int red = -1;
        int green = -1;
        for (int index = 0; index < commands.size(); index++) {
            JsonNode command = commands.get(index);
            String phase = command.path("phase").asText();
            int exitCode = command.path("exitCode").asInt(Integer.MIN_VALUE);
            if (red < 0 && "RED".equals(phase) && exitCode != 0) {
                red = index;
            }
            if ("GREEN".equals(phase) && exitCode == 0) {
                green = index;
            }
        }
        return red >= 0 && green > red;
    }

    private boolean focusedTestsPassed(GateEvaluationContext context) {
        JsonNode commands = commands(context);
        if (commands == null) {
            return false;
        }
        boolean found = false;
        for (JsonNode command : commands) {
            String phase = command.path("phase").asText();
            if ("GREEN".equals(phase) || "VERIFY".equals(phase)) {
                found = true;
                if (command.path("exitCode").asInt(Integer.MIN_VALUE) != 0) {
                    return false;
                }
            }
        }
        return found;
    }

    private boolean implementationTraceabilityComplete(GateEvaluationContext context) {
        JsonNode root = json(context.artifact(ArtifactType.TRACEABILITY));
        JsonNode links = root == null ? null : root.path("links");
        if (links == null || !links.isArray() || links.isEmpty()) {
            return false;
        }
        for (JsonNode link : links) {
            if (!nonBlank(link, "requirementId") || !nonBlank(link, "acceptanceCriteriaId")
                    || !nonBlank(link, "designRef") || !nonBlank(link, "testRef")
                    || !nonBlank(link, "implementationRef")) {
                return false;
            }
        }
        return true;
    }

    private boolean noSensitiveFileChange(GateEvaluationContext context) {
        JsonNode changed = json(context.artifact(ArtifactType.CHANGED_FILES));
        JsonNode files = changed == null ? null : changed.path("files");
        if (files == null || !files.isArray() || files.isEmpty()) {
            return false;
        }
        for (JsonNode file : files) {
            String path = file.path("path").asText().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (!nonBlank(file, "path") || file.path("sensitive").asBoolean(false)
                    || path.equals("env.local") || path.startsWith("data/")
                    || path.contains("secrets.properties") || path.startsWith(".codex/")) {
                return false;
            }
        }
        return true;
    }

    private boolean allAcceptanceCriteriaPassed(GateEvaluationContext context) {
        JsonNode root = json(context.artifact(ArtifactType.ACCEPTANCE_RESULT));
        JsonNode criteria = root == null ? null : root.path("criteria");
        if (criteria == null || !criteria.isArray() || criteria.isEmpty()) {
            return false;
        }
        for (JsonNode criterion : criteria) {
            if (!nonBlank(criterion, "id") || !criterion.path("passed").asBoolean(false)) {
                return false;
            }
        }
        return true;
    }

    private JsonNode commands(GateEvaluationContext context) {
        JsonNode root = json(context.artifact(ArtifactType.TEST_EVIDENCE));
        JsonNode commands = root == null ? null : root.path("commands");
        return commands != null && commands.isArray() && !commands.isEmpty() ? commands : null;
    }

    private boolean booleanField(GateEvaluationContext context, ArtifactType type, String field) {
        JsonNode root = json(context.artifact(type));
        return root != null && root.path(field).asBoolean(false);
    }

    private int integerField(GateEvaluationContext context, ArtifactType type, String field) {
        JsonNode root = json(context.artifact(type));
        return root == null ? Integer.MIN_VALUE : root.path(field).asInt(Integer.MIN_VALUE);
    }

    private boolean nonBlank(GateEvaluationContext context, ArtifactType type) {
        GateArtifact artifact = context.artifact(type);
        return artifact != null && !artifact.text().trim().isEmpty();
    }

    private boolean nonBlank(JsonNode node, String field) {
        return node != null && node.path(field).isTextual()
                && !node.path(field).asText().trim().isEmpty();
    }

    private JsonNode json(GateArtifact artifact) {
        if (artifact == null) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(artifact.getContent().copyBytes());
            return root != null && root.isObject() ? root : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private List<String> evidence(GateEvaluationContext context) {
        List<String> references = new ArrayList<String>();
        for (GateArtifact artifact : context.artifacts()) {
            ArtifactDescriptor descriptor = artifact.getDescriptor();
            references.add(descriptor.getArtifactId() + '@' + descriptor.getVersion()
                    + ':' + descriptor.getSha256());
        }
        Collections.sort(references);
        return references;
    }

    private static Set<ArtifactType> jsonTypes() {
        Set<ArtifactType> types = java.util.EnumSet.noneOf(ArtifactType.class);
        Collections.addAll(types, ArtifactType.ACCEPTANCE_CRITERIA, ArtifactType.OPEN_QUESTIONS,
                ArtifactType.CHANGE_PLAN, ArtifactType.TEST_STRATEGY, ArtifactType.CHANGED_FILES,
                ArtifactType.TEST_EVIDENCE, ArtifactType.TRACEABILITY, ArtifactType.PREFLIGHT,
                ArtifactType.BUILD_EVIDENCE, ArtifactType.DEPLOYMENT_RECORD,
                ArtifactType.ACCEPTANCE_RESULT);
        return Collections.unmodifiableSet(types);
    }
}
