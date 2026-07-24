package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * build/deploy/health/acceptance 的有序结果；失败即停止，ROLLBACK 不自动执行。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class DeploymentOutcome {

    private static final DeploymentStep[] EXECUTED_STEPS = {
            DeploymentStep.BUILD, DeploymentStep.DEPLOY,
            DeploymentStep.HEALTH_CHECK, DeploymentStep.ACCEPTANCE
    };

    private final List<DeploymentStepResult> results;
    private final boolean successful;
    private final String failureReason;

    public DeploymentOutcome(List<DeploymentStepResult> results) {
        if (results == null || results.isEmpty() || results.size() > EXECUTED_STEPS.length) {
            throw new IllegalArgumentException("deployment outcome results are incomplete");
        }
        List<DeploymentStepResult> copy = new ArrayList<DeploymentStepResult>(results.size());
        boolean stopped = false;
        for (int index = 0; index < results.size(); index++) {
            DeploymentStepResult result = results.get(index);
            if (result == null || result.getStep() != EXECUTED_STEPS[index] || stopped) {
                throw new IllegalArgumentException("deployment outcome order is invalid");
            }
            copy.add(result);
            stopped = !result.passed();
        }
        DeploymentStepResult last = copy.get(copy.size() - 1);
        this.successful = copy.size() == EXECUTED_STEPS.length && last.passed();
        if (!successful && last.passed()) {
            throw new IllegalArgumentException("deployment outcome cannot stop after a passed step");
        }
        this.failureReason = successful ? null
                : "deployment step failed: " + last.getStep();
        this.results = Collections.unmodifiableList(copy);
    }

    public DeploymentStepResult result(DeploymentStep step) {
        for (DeploymentStepResult result : results) {
            if (result.getStep() == step) {
                return result;
            }
        }
        return null;
    }

    public Map<DeploymentStep, DeploymentStepResult> resultMap() {
        Map<DeploymentStep, DeploymentStepResult> values =
                new EnumMap<DeploymentStep, DeploymentStepResult>(DeploymentStep.class);
        for (DeploymentStepResult result : results) {
            values.put(result.getStep(), result);
        }
        return Collections.unmodifiableMap(values);
    }
}
