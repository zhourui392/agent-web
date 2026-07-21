package com.example.agentweb.app.requirement;

import com.example.agentweb.domain.shared.AgentType;
import lombok.Value;

import java.util.Map;

/**
 * 一次需求线 run 的执行参数（发射器输入）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Value
public class RunProfile {

    /** plan / implement / verify，进 SSE 流 key 与日志 */
    String runKind;

    AgentType agentType;

    String workingDir;

    /** 已过 PromptAssemblyService 的最终 prompt */
    String assembledPrompt;

    long timeoutSeconds;

    /** 附加进程 env（AGENT_DEV_PORT 等），可空 */
    Map<String, String> extraEnv;
}
