package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.RunMode;

/**
 * Workbench 总开关、创建和写 Run 发布开关的不可变求交策略。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchReleasePolicy {

    private final boolean enabled;
    private final boolean createEnabled;
    private final boolean writeRunEnabled;

    public WorkbenchReleasePolicy(
            boolean enabled, boolean createEnabled,
            boolean writeRunEnabled) {
        this.enabled = enabled;
        this.createEnabled = createEnabled;
        this.writeRunEnabled = writeRunEnabled;
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

}
