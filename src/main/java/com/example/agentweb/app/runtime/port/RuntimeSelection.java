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

    private final AgentType agentType;
    private final RuntimeVersionPolicy runtimeVersionPolicy;

    public RuntimeSelection(AgentType agentType,
                            RuntimeVersionPolicy runtimeVersionPolicy) {
        this.agentType = Objects.requireNonNull(agentType, "agentType");
        this.runtimeVersionPolicy = Objects.requireNonNull(
                runtimeVersionPolicy, "runtimeVersionPolicy");
    }
}
