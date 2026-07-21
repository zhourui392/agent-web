package com.example.agentweb.app.agentrun;

import org.springframework.stereotype.Component;

/**
 * Appends original user input unless legacy historical RAG already owns it.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Component
public class UserInputContributor implements PromptContributor {

    @Override
    public void append(PromptAssembly assembly) {
        if (assembly.isUserInputOwnedByHistoricalRag()) {
            return;
        }
        assembly.addPart(PromptPartType.USER_INPUT, "User Input",
                "[USER_INPUT]\n" + nullToEmpty(assembly.getContext().getOriginalInput()));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
