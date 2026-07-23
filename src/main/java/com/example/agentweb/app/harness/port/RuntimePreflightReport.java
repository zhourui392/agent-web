package com.example.agentweb.app.harness.port;

import com.example.agentweb.domain.harness.RuntimeEnforcementProfile;
import com.example.agentweb.domain.harness.WorkspaceRuntimeInventory;
import lombok.Getter;

/**
 * Runtime Preflight 采集的强制能力与工作区技术事实。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class RuntimePreflightReport {

    private final RuntimeEnforcementProfile enforcementProfile;
    private final WorkspaceRuntimeInventory workspaceInventory;

    public RuntimePreflightReport(RuntimeEnforcementProfile enforcementProfile,
                                  WorkspaceRuntimeInventory workspaceInventory) {
        if (enforcementProfile == null || workspaceInventory == null) {
            throw new IllegalArgumentException("runtime preflight report must be complete");
        }
        this.enforcementProfile = enforcementProfile;
        this.workspaceInventory = workspaceInventory;
    }
}
