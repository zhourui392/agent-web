package com.example.agentweb.domain.mode;

import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;

import java.util.Collections;
import java.util.List;

/**
 * 一次模式会话 Run 的已解析能力：可重验绑定 + 命令内容清单。
 *
 * @author alex
 * @since 2026-08-20
 */
public final class ResolvedModeCapabilities {

    private final ResolvedCapabilityBinding binding;
    private final List<CommandDefinition> commands;

    public ResolvedModeCapabilities(ResolvedCapabilityBinding binding,
                                    List<CommandDefinition> commands) {
        this.binding = java.util.Objects.requireNonNull(binding, "binding");
        this.commands = commands == null
                ? Collections.<CommandDefinition>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(commands));
    }

    public ResolvedCapabilityBinding getBinding() {
        return binding;
    }

    public List<CommandDefinition> getCommands() {
        return commands;
    }
}
