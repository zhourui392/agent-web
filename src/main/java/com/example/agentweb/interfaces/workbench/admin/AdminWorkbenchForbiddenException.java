package com.example.agentweb.interfaces.workbench.admin;

/**
 * 已登录用户不具备 Admin Workbench 权限。
 *
 * @author alex
 * @since 2026-08-01
 */
final class AdminWorkbenchForbiddenException extends RuntimeException {

    AdminWorkbenchForbiddenException() {
        super("administrator role is required");
    }
}
