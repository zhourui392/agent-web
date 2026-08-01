package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

/**
 * Runtime Preflight 成功后可固化的非敏感技术事实。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimePreflightReport {

    private final AgentType agentType;
    private final String runtimeVersion;
    private final String compatibilityMatrixId;
    private final SandboxMode sandboxMode;
    private final int readableRootCount;
    private final int writableRootCount;
    private final boolean multiRepository;
    private final String capabilityBindingHash;

    public RuntimePreflightReport(
            AgentType agentType, String runtimeVersion,
            String compatibilityMatrixId, SandboxMode sandboxMode,
            int readableRootCount, int writableRootCount,
            String capabilityBindingHash) {
        if (agentType == null || sandboxMode == null) {
            throw new IllegalArgumentException(
                    "runtime preflight identity and sandbox are required");
        }
        if (readableRootCount < 1 || writableRootCount < 0
                || writableRootCount > readableRootCount
                || (sandboxMode == SandboxMode.READ_ONLY
                && writableRootCount != 0)
                || (sandboxMode == SandboxMode.WORKSPACE_WRITE
                && writableRootCount == 0)) {
            throw new IllegalArgumentException(
                    "runtime preflight root counts are inconsistent");
        }
        this.agentType = agentType;
        this.runtimeVersion = DomainText.require(
                runtimeVersion, "runtime preflight version", 160);
        this.compatibilityMatrixId = DomainText.require(
                compatibilityMatrixId,
                "runtime compatibility matrix id", 240);
        this.sandboxMode = sandboxMode;
        this.readableRootCount = readableRootCount;
        this.writableRootCount = writableRootCount;
        this.multiRepository = readableRootCount > 1;
        this.capabilityBindingHash = DomainText.requireSha256(
                capabilityBindingHash, "runtime capability binding hash");
    }
}
