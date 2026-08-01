package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.shared.AgentType;
import lombok.Getter;

import java.util.Objects;

/**
 * 已选 Runtime、版本约束和无明文 Secret 的凭据引用。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeSelection {

    private final AgentType agentType;
    private final RuntimeVersionPolicy runtimeVersionPolicy;
    private final CredentialReference credentialReference;

    public RuntimeSelection(AgentType agentType,
                            RuntimeVersionPolicy runtimeVersionPolicy,
                            CredentialReference credentialReference) {
        this.agentType = Objects.requireNonNull(agentType, "agentType");
        this.runtimeVersionPolicy = Objects.requireNonNull(
                runtimeVersionPolicy, "runtimeVersionPolicy");
        this.credentialReference = Objects.requireNonNull(
                credentialReference, "credentialReference");
    }
}
