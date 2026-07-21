package com.example.agentweb.domain.shared;

/**
 * Supported agent executors. Extend when adding new CLI implementations.
 * @author zhourui(V33215020)
 */
public enum AgentType {
    /** OpenAI Codex CLI (codex exec --json). */
    CODEX,
    /** Anthropic Claude Code CLI. */
    CLAUDE,
    /** In-process diagnosis engine backed by cclc-agent-diagnosis. */
    NATIVE;

    /**
     * 是否为用户/管理后台可选的 CLI agent。
     * <p>{@link #NATIVE} 是进程内诊断引擎, 不通过对话/诊断默认模型开关暴露, 故不可选。</p>
     *
     * @return 可作为默认模型选项时为 true
     */
    public boolean isSelectable() {
        return this != NATIVE;
    }

    /**
     * 把外部输入(后台表单 / API)解析为可选的 {@link AgentType}。
     * <p>大小写不敏感、去除首尾空白; null/空白、未知值、不可选值(如 NATIVE)一律抛
     * {@link IllegalArgumentException}, 由调用方转 400。</p>
     *
     * @param input 原始字符串
     * @return 解析出的可选 agent 类型
     * @throws IllegalArgumentException 输入为空、未知或不可选
     */
    public static AgentType parseSelectable(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("agentType is blank");
        }
        AgentType type;
        try {
            type = AgentType.valueOf(input.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown agentType: " + input, e);
        }
        if (!type.isSelectable()) {
            throw new IllegalArgumentException("agentType not selectable: " + input);
        }
        return type;
    }
}
