package com.example.agentweb.app.runtime.port;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 会话模式快照映射到单次 Runtime 的 Claude 能力下发事实。
 *
 * <p>由计划装配层从 {@code ModeSnapshot} 解析生成；Runtime 只物化与下发，
 * 不做模式语义判断。无模式会话为 {@code null}（零行为变化）。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
public final class ModeRuntimeDelivery {

    private final String appendSystemPrompt;
    private final String permissionMode;
    private final List<CommandPayload> commands;

    public ModeRuntimeDelivery(String appendSystemPrompt, String permissionMode,
                               List<CommandPayload> commands) {
        this.appendSystemPrompt = appendSystemPrompt;
        this.permissionMode = permissionMode;
        this.commands = commands == null
                ? Collections.<CommandPayload>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(commands));
        if (this.commands.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "mode runtime delivery commands must be complete");
        }
    }

    public String getAppendSystemPrompt() {
        return appendSystemPrompt;
    }

    /** CLI 原始值；null 表示不覆盖。 */
    public String getPermissionMode() {
        return permissionMode;
    }

    public List<CommandPayload> getCommands() {
        return commands;
    }

    /**
     * 已按快照 hash 重验的命令内容（markdown prompt template）。
     */
    public static final class CommandPayload {

        private final String identifier;
        private final String contentMarkdown;

        public CommandPayload(String identifier, String contentMarkdown) {
            this.identifier = Objects.requireNonNull(identifier, "identifier");
            this.contentMarkdown = Objects.requireNonNull(contentMarkdown, "contentMarkdown");
        }

        public String getIdentifier() {
            return identifier;
        }

        public String getContentMarkdown() {
            return contentMarkdown;
        }
    }
}
