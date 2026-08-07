package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.RunMode;

/**
 * 应用层选择受控 Runtime Profile 的端口。
 *
 * @author alex
 * @since 2026-08-07
 */
public interface RuntimeProfileSelector {

    boolean hasProfiles();

    RuntimeSelection selection(AgentType agentType, AgentRuntimeSurface surface,
                               RunMode runMode, String profileId,
                               String model, String reasoningEffort);
}
