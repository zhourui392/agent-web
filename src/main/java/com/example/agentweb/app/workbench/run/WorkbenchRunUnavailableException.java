package com.example.agentweb.app.workbench.run;

/**
 * Workbench 公共 Runtime 路径未启用时的稳定应用异常。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchRunUnavailableException
        extends RuntimeException {

    public WorkbenchRunUnavailableException() {
        super("workbench run service is unavailable");
    }
}
