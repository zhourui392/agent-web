package com.example.agentweb.app.workbench.run;

/**
 * Workbench、Owner、Snapshot 或 ChatRun exact binding 不可见时的统一异常。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchRunNotFoundException extends RuntimeException {

    public WorkbenchRunNotFoundException() {
        super("workbench run is not available");
    }
}
