package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import lombok.Getter;

import java.util.Objects;

/**
 * Runtime Preflight 的完整中性输入，不包含消费者阶段或交付语义。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimePreflightRequest {

    private final RuntimeSelection runtimeSelection;
    private final WorkspaceLayout workspaceLayout;
    private final ResolvedCapabilityBinding capabilityBinding;

    public RuntimePreflightRequest(
            RuntimeSelection runtimeSelection,
            WorkspaceLayout workspaceLayout,
            ResolvedCapabilityBinding capabilityBinding) {
        this.runtimeSelection = Objects.requireNonNull(
                runtimeSelection, "runtimeSelection");
        this.workspaceLayout = Objects.requireNonNull(
                workspaceLayout, "workspaceLayout");
        this.capabilityBinding = Objects.requireNonNull(
                capabilityBinding, "capabilityBinding");
    }
}
