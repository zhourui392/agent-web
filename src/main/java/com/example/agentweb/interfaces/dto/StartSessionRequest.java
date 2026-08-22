package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class StartSessionRequest {
    /**
     * Agent type: CODEX or CLAUDE.
     * 可为空: 留空时由后端 AgentTypeResolver 回退到 agent.default-type.
     */
    private String agentType;

    @NotBlank
    private String workingDir;

    /**
     * 可为空: 对应 EnvProperties 的 key (如 test/prod), 为空表示无环境约束.
     */
    private String env;

    /**
     * 可为空: 绑定的模式 id, 为空表示默认模式（原纯 Chat 行为）。
     * 绑定模式要求 agentType 解析为 CLAUDE（模式能力仅 Claude 方言支持）。
     */
    private String modeId;
}
