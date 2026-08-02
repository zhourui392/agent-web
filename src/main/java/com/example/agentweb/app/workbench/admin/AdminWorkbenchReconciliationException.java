package com.example.agentweb.app.workbench.admin;

/**
 * Admin 单 Run 对账失败的安全应用异常。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class AdminWorkbenchReconciliationException
        extends RuntimeException {

    public AdminWorkbenchReconciliationException(Throwable cause) {
        super("workbench run reconciliation failed", cause);
    }
}
