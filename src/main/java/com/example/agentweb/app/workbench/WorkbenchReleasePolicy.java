package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.HighImpactOperationType;
import com.example.agentweb.domain.workbench.RunMode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Workbench 总开关与细粒度发布开关的不可变求交策略。
 *
 * <p>授权卡片只表达领域授权事实，不参与 Executor 发布决策。每次真实高影响
 * 副作用都必须使用操作类型重新查询本策略。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchReleasePolicy {

    private final boolean enabled;
    private final boolean createEnabled;
    private final boolean writeRunEnabled;
    private final Map<HighImpactOperationType, Boolean>
            highImpactExecutionByType;

    public WorkbenchReleasePolicy(
            boolean enabled, boolean createEnabled,
            boolean writeRunEnabled, boolean commitEnabled,
            boolean pushEnabled, boolean localDeployEnabled,
            boolean productionWriteEnabled) {
        this.enabled = enabled;
        this.createEnabled = createEnabled;
        this.writeRunEnabled = writeRunEnabled;
        EnumMap<HighImpactOperationType, Boolean> availability =
                new EnumMap<HighImpactOperationType, Boolean>(
                        HighImpactOperationType.class);
        availability.put(
                HighImpactOperationType.GIT_COMMIT, commitEnabled);
        availability.put(
                HighImpactOperationType.GIT_PUSH, pushEnabled);
        availability.put(
                HighImpactOperationType.LOCAL_DEPLOY,
                localDeployEnabled);
        availability.put(
                HighImpactOperationType.PRODUCTION_WRITE,
                productionWriteEnabled);
        this.highImpactExecutionByType = Collections.unmodifiableMap(
                availability);
    }

    public void requireCreationAvailable() {
        if (!enabled || !createEnabled) {
            throw WorkbenchReleaseUnavailableException.creation();
        }
    }

    public void requireRunAvailable(RunMode runMode) {
        if (runMode == null) {
            throw new IllegalArgumentException(
                    "workbench run mode must not be null");
        }
        if (!enabled
                || (runMode.modifiesWorkspace() && !writeRunEnabled)) {
            throw WorkbenchReleaseUnavailableException.run();
        }
    }

    public boolean isHighImpactExecutionAvailable(
            HighImpactOperationType operationType) {
        if (operationType == null) {
            throw new IllegalArgumentException(
                    "high-impact operation type must not be null");
        }
        return enabled && Boolean.TRUE.equals(
                highImpactExecutionByType.get(operationType));
    }

    public void requireHighImpactExecutionAvailable(
            HighImpactOperationType operationType) {
        if (!isHighImpactExecutionAvailable(operationType)) {
            throw WorkbenchReleaseUnavailableException
                    .highImpactExecution();
        }
    }
}
