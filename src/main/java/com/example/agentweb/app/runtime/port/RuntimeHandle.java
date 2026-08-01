package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Application 可持有的稳定 Runtime Handle；不暴露 Process 或 Provider SDK 类型。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class RuntimeHandle {

    private final String executionId;
    private final String handleId;

    public RuntimeHandle(String executionId, String handleId) {
        this.executionId = DomainText.require(executionId, "runtime execution id", 160);
        this.handleId = DomainText.require(handleId, "runtime handle id", 500);
    }
}
