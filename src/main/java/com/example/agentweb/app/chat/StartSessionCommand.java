package com.example.agentweb.app.chat;

/**
 * 会话创建入参；modeId 可空（null = 默认模式，原纯 Chat 行为）。
 *
 * @author zhourui(V33215020)
 */
public record StartSessionCommand(String agentType, String workingDir, String env, String modeId) {

    /** 兼容旧调用方（无模式）。 */
    public StartSessionCommand(String agentType, String workingDir, String env) {
        this(agentType, workingDir, env, null);
    }
}
