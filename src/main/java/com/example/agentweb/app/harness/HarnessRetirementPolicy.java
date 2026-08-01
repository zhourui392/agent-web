package com.example.agentweb.app.harness;

/**
 * Harness 退役窗口的创建、写入与导出分级可用性策略。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class HarnessRetirementPolicy {

    private final boolean creationEnabled;
    private final boolean mutationEnabled;
    private final boolean exportEnabled;

    public HarnessRetirementPolicy(
            boolean creationEnabled, boolean mutationEnabled,
            boolean exportEnabled) {
        this.creationEnabled = creationEnabled;
        this.mutationEnabled = mutationEnabled;
        this.exportEnabled = exportEnabled;
    }

    public void requireCreationAvailable() {
        requireMutationAvailable();
        if (!creationEnabled) {
            throw HarnessRetirementUnavailableException.creation();
        }
    }

    public void requireMutationAvailable() {
        if (!mutationEnabled) {
            throw HarnessRetirementUnavailableException.mutation();
        }
    }

    public void requireExportAvailable() {
        if (!exportEnabled) {
            throw HarnessRetirementUnavailableException.export();
        }
    }
}
