package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 阶段输入、输出、确定性门禁和人工审批合同值对象。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class StageContract {

    private final HarnessStage stage;
    private final Set<ArtifactType> requiredInputArtifacts;
    private final Set<ArtifactType> requiredOutputArtifacts;
    private final Set<String> deterministicGates;
    private final String approvalType;

    public StageContract(HarnessStage stage,
                         Set<ArtifactType> requiredInputArtifacts,
                         Set<ArtifactType> requiredOutputArtifacts,
                         Set<String> deterministicGates,
                         String approvalType) {
        if (stage == null) {
            throw new IllegalArgumentException("contract stage must not be null");
        }
        this.stage = stage;
        this.requiredInputArtifacts = immutableArtifactTypes(requiredInputArtifacts, false, "input artifacts");
        this.requiredOutputArtifacts = immutableArtifactTypes(requiredOutputArtifacts, true, "output artifacts");
        this.deterministicGates = immutableRules(deterministicGates);
        this.approvalType = DomainText.require(approvalType, "approval type");
    }

    /**
     * 返回与 M0 stage-contracts.json 1.0.0 一致的首版合同。
     *
     * @return 固定顺序的不可变合同
     */
    public static List<StageContract> mvpDefaults() {
        List<StageContract> contracts = new ArrayList<StageContract>();
        contracts.add(new StageContract(
                HarnessStage.ANALYSIS,
                artifactTypes(ArtifactType.ORIGINAL_REQUIREMENT),
                artifactTypes(ArtifactType.REQUIREMENT, ArtifactType.ACCEPTANCE_CRITERIA,
                        ArtifactType.IMPACT_ANALYSIS, ArtifactType.OPEN_QUESTIONS),
                rules("required-artifacts-present", "artifact-schema-valid",
                        "requirement-ids-unique", "acceptance-criteria-observable",
                        "no-blocking-open-question"),
                "REQUIREMENT_BASELINE"));
        contracts.add(new StageContract(
                HarnessStage.DESIGN,
                artifactTypes(ArtifactType.REQUIREMENT, ArtifactType.ACCEPTANCE_CRITERIA,
                        ArtifactType.IMPACT_ANALYSIS),
                artifactTypes(ArtifactType.SOLUTION, ArtifactType.CHANGE_PLAN,
                        ArtifactType.TEST_STRATEGY, ArtifactType.DEPLOYMENT_PLAN,
                        ArtifactType.ROLLBACK_PLAN, ArtifactType.TRACEABILITY),
                rules("required-artifacts-present", "artifact-schema-valid",
                        "requirement-design-coverage-complete", "requirement-test-coverage-complete",
                        "layering-decision-present", "rollback-plan-present"),
                "DESIGN_BASELINE"));
        contracts.add(new StageContract(
                HarnessStage.IMPLEMENTATION,
                artifactTypes(ArtifactType.REQUIREMENT, ArtifactType.ACCEPTANCE_CRITERIA,
                        ArtifactType.SOLUTION, ArtifactType.CHANGE_PLAN, ArtifactType.TEST_STRATEGY),
                artifactTypes(ArtifactType.CHANGED_FILES, ArtifactType.TEST_EVIDENCE,
                        ArtifactType.IMPLEMENTATION_SUMMARY, ArtifactType.TRACEABILITY),
                rules("required-artifacts-present", "artifact-schema-valid",
                        "git-baseline-unchanged-or-explained",
                        "tdd-evidence-present-for-business-branches", "focused-tests-passed",
                        "traceability-complete", "no-sensitive-file-change"),
                "DEPLOYABLE_BASELINE"));
        contracts.add(new StageContract(
                HarnessStage.DEPLOYMENT,
                artifactTypes(ArtifactType.ACCEPTANCE_CRITERIA, ArtifactType.DEPLOYMENT_PLAN,
                        ArtifactType.ROLLBACK_PLAN, ArtifactType.CHANGED_FILES,
                        ArtifactType.TEST_EVIDENCE, ArtifactType.TRACEABILITY),
                artifactTypes(ArtifactType.PREFLIGHT, ArtifactType.BUILD_EVIDENCE,
                        ArtifactType.DEPLOYMENT_RECORD, ArtifactType.ACCEPTANCE_RESULT,
                        ArtifactType.FINAL_REPORT),
                rules("required-artifacts-present", "artifact-schema-valid",
                        "approved-git-baseline-matches", "build-passed",
                        "local-health-check-passed", "acceptance-criteria-passed"),
                "DELIVERY_COMPLETE"));
        return Collections.unmodifiableList(contracts);
    }

    /**
     * 生成可进入 Prompt 快照的稳定合同摘要，避免 Application 遍历 getter 重组规则。
     *
     * @return 固定字段顺序的合同文本
     */
    public String promptSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("stage: ").append(stage).append('\n');
        summary.append("requiredInputs: ").append(enumNames(requiredInputArtifacts)).append('\n');
        summary.append("requiredOutputs: ").append(enumNames(requiredOutputArtifacts)).append('\n');
        summary.append("deterministicGates: ").append(String.join(",", deterministicGates)).append('\n');
        summary.append("approvalType: ").append(approvalType);
        return summary.toString();
    }

    private static Set<ArtifactType> artifactTypes(ArtifactType... values) {
        return Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(values)));
    }

    private static Set<String> rules(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }

    private Set<ArtifactType> immutableArtifactTypes(Set<ArtifactType> values,
                                                     boolean requireNonEmpty,
                                                     String name) {
        if (values == null || requireNonEmpty && values.isEmpty()) {
            throw new IllegalArgumentException("contract " + name + " must not be empty");
        }
        if (values.isEmpty()) {
            return Collections.emptySet();
        }
        if (values.contains(null)) {
            throw new IllegalArgumentException("contract " + name + " must not contain null");
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    private Set<String> immutableRules(Set<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("deterministic gates must not be empty");
        }
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : values) {
            copy.add(DomainText.require(value, "deterministic gate"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private String enumNames(Set<ArtifactType> values) {
        List<String> names = new ArrayList<String>();
        for (ArtifactType value : values) {
            names.add(value.name());
        }
        return String.join(",", names);
    }
}
