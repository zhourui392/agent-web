package com.example.agentweb.interfaces.workbench.admin;

/**
 * Admin Workbench 请求缺少登录身份。
 *
 * @author alex
 * @since 2026-08-01
 */
final class AdminWorkbenchUnauthorizedException extends RuntimeException {

    AdminWorkbenchUnauthorizedException() {
        super("administrator login is required");
    }
}
