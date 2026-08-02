package com.example.agentweb.interfaces.workbench.admin;

/**
 * Admin Workbench 安全投影不存在。
 *
 * @author alex
 * @since 2026-08-01
 */
final class AdminWorkbenchNotFoundException extends RuntimeException {

    AdminWorkbenchNotFoundException() {
        super("workbench was not found");
    }
}
