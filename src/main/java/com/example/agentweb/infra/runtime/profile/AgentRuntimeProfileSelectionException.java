package com.example.agentweb.infra.runtime.profile;

/** Profile 选择失败；首期统一映射为 RUNTIME_PROFILE_NOT_FOUND。 */
public final class AgentRuntimeProfileSelectionException extends RuntimeException {

    public AgentRuntimeProfileSelectionException(String message) {
        super(message);
    }
}
