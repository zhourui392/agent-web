package com.example.agentweb.app.workbench.admin;

/**
 * Admin Workbench Run 不存在或 exact binding 不成立。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class AdminWorkbenchRunNotFoundException
        extends RuntimeException {

    public AdminWorkbenchRunNotFoundException() {
        super("workbench run was not found");
    }
}
