package com.example.agentweb.interfaces.workbench.admin.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Admin Stop/Reconcile 无参数请求；未知字段 fail-closed，禁止注入 Owner 或动作参数。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class AdminWorkbenchActionRequest {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported admin workbench action field: " + field);
    }
}
