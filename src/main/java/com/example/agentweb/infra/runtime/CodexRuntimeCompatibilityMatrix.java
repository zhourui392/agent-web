package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimePreflightErrorCode;
import com.example.agentweb.app.runtime.port.RuntimePreflightException;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 已验证 Codex 版本集合对应的 Sandbox 和多仓布局兼容矩阵。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class CodexRuntimeCompatibilityMatrix {

    private final String matrixId;
    private final Set<SandboxMode> supportedSandboxModes;
    private final boolean multiRepositorySupported;

    public CodexRuntimeCompatibilityMatrix(
            String matrixId, Set<SandboxMode> supportedSandboxModes,
            boolean multiRepositorySupported) {
        this.matrixId = DomainText.require(
                matrixId, "Codex compatibility matrix id", 240);
        if (supportedSandboxModes == null
                || supportedSandboxModes.isEmpty()
                || supportedSandboxModes.contains(null)) {
            throw new IllegalArgumentException(
                    "Codex compatibility matrix sandbox modes must be complete");
        }
        this.supportedSandboxModes = Collections.unmodifiableSet(
                EnumSet.copyOf(supportedSandboxModes));
        this.multiRepositorySupported = multiRepositorySupported;
    }

    void requireCompatible(
            String bindingRuntimeCompatibility,
            WorkspaceLayout workspaceLayout) {
        if (!matrixId.equals(bindingRuntimeCompatibility)) {
            throw new RuntimePreflightException(
                    RuntimePreflightErrorCode.RUNTIME_COMPATIBILITY_MISMATCH,
                    "Runtime capability binding does not match the compatibility matrix");
        }
        if (!supportedSandboxModes.contains(workspaceLayout.getSandboxMode())
                || (workspaceLayout.getReadableRoots().size() > 1
                && !multiRepositorySupported)) {
            throw new RuntimePreflightException(
                    RuntimePreflightErrorCode.RUNTIME_LAYOUT_UNSUPPORTED,
                    "Runtime workspace layout is not verified by the compatibility matrix");
        }
    }
}
