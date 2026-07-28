package com.example.agentweb.domain.slashcommand;

import lombok.Getter;

@Getter
public class SlashExpansionResult {
    private final String expandedPrompt;
    private final boolean matched;
    private final boolean skill;
    private final String commandName;
    private final String arguments;

    public SlashExpansionResult(String expandedPrompt, boolean matched, boolean skill,
                                String commandName, String arguments) {
        this.expandedPrompt = expandedPrompt;
        this.matched = matched;
        this.skill = skill;
        this.commandName = commandName;
        this.arguments = arguments;
    }
}
