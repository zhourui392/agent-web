package com.example.agentweb.infra;

import lombok.Getter;
import lombok.Setter;
import com.example.agentweb.domain.shared.AgentType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 全局默认配置.
 * <p>YAML:
 * <pre>
 * agent:
 *   default-type: CODEX   # 或 CLAUDE
 * </pre>
 * 未配置时使用 {@link AgentType#CODEX} 兜底. Claude 通道已废弃隐藏(不下线), 仅存量会话续用.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
@Component
@ConfigurationProperties(prefix = "agent")
@Getter
@Setter
public class AgentDefaultProperties {

    /** 创建新会话且 UI 未显式指定 agentType 时使用的默认值. */
    private AgentType defaultType = AgentType.CODEX;
}
