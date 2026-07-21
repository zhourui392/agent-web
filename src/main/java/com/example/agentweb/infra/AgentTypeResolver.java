package com.example.agentweb.infra;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.setting.RuntimeAgentSettings;
import org.springframework.stereotype.Component;

/**
 * Agent 类型解析器.
 * <p>把可能为 null/空/大小写不一的字符串 (来自 UI 表单 / API 请求) 解析为 {@link AgentType} 枚举,
 * 缺失时回退到运行时默认值 {@link RuntimeAgentSettings#getChatDefaultAgent()}
 * (管理后台可改、免重启; 其内部再回退 yml 种子 {@link AgentDefaultProperties}).</p>
 *
 * <p>抽出独立类是为了让"含 if/switch 业务判断"的解析逻辑可独立 TDD,
 * 调用方 (ChatAppServiceImpl) 仅作组合调用, 不再含业务判断.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-14
 */
@Component
public class AgentTypeResolver {

    private final RuntimeAgentSettings runtimeAgentSettings;

    public AgentTypeResolver(RuntimeAgentSettings runtimeAgentSettings) {
        this.runtimeAgentSettings = runtimeAgentSettings;
    }

    public AgentType resolve(String input) {
        if (input == null || input.trim().isEmpty()) {
            return runtimeAgentSettings.getChatDefaultAgent();
        }
        String normalized = input.trim().toUpperCase();
        try {
            return AgentType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown agentType: " + input, e);
        }
    }
}
