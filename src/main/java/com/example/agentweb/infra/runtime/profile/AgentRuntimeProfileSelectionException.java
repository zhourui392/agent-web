package com.example.agentweb.infra.runtime.profile;

/** Profile 选择失败；首期统一映射为 RUNTIME_PROFILE_NOT_FOUND。
 *
 * @author alex
 * @since 2026-08-07
 */
public final class AgentRuntimeProfileSelectionException extends RuntimeException {

    public AgentRuntimeProfileSelectionException(String message) {
        super(message);
    }
}
