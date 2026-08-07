package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.shared.AgentType;
import lombok.Getter;

import java.util.Objects;

/**
 * 已选 Runtime 和版本约束。
 *
 * <p>单用户本机模式下 Codex 子进程直接继承服务进程用户的登录态，
 * 不再携带凭据引用。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeSelection {

    private final String profileId;
    private final AgentType agentType;
    private final String endpoint;
    private final String model;
    private final String reasoningEffort;
    private final String runtimeEnvironment;
    private final RuntimeVersionPolicy runtimeVersionPolicy;

    public RuntimeSelection(AgentType agentType,
                            RuntimeVersionPolicy runtimeVersionPolicy) {
        this(null, agentType, null, null, null, null, runtimeVersionPolicy);
    }

    public RuntimeSelection(String profileId, AgentType agentType, String endpoint,
                            String model, String reasoningEffort,
                            String runtimeEnvironment,
                            RuntimeVersionPolicy runtimeVersionPolicy) {
        this.profileId = profileId == null || profileId.trim().isEmpty()
                ? null : profileId.trim();
        this.agentType = Objects.requireNonNull(agentType, "agentType");
        this.endpoint = endpoint == null || endpoint.trim().isEmpty()
                ? null : endpoint.trim();
        this.model = model == null || model.trim().isEmpty() ? null : model.trim();
        this.reasoningEffort = reasoningEffort == null || reasoningEffort.trim().isEmpty()
                ? null : reasoningEffort.trim();
        this.runtimeEnvironment = runtimeEnvironment == null
                || runtimeEnvironment.trim().isEmpty() ? null : runtimeEnvironment.trim();
        this.runtimeVersionPolicy = Objects.requireNonNull(
                runtimeVersionPolicy, "runtimeVersionPolicy");
    }
}
