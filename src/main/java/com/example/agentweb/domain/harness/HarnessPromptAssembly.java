package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Prompt Parts、最终文本和最终 Hash。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class HarnessPromptAssembly {

    private final List<HarnessPromptPart> parts;
    private final String finalPrompt;
    private final String promptHash;

    public HarnessPromptAssembly(List<HarnessPromptPart> parts, String finalPrompt, String promptHash) {
        if (parts == null || parts.isEmpty() || parts.contains(null)) {
            throw new IllegalArgumentException("prompt parts must not be empty or contain null");
        }
        this.parts = Collections.unmodifiableList(new ArrayList<HarnessPromptPart>(parts));
        if (finalPrompt == null || finalPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException("final prompt must not be blank");
        }
        this.finalPrompt = finalPrompt;
        this.promptHash = DomainText.requireSha256(promptHash, "final prompt hash");
        if (!HarnessHashing.sha256(finalPrompt).equals(this.promptHash)) {
            throw new IllegalArgumentException("final prompt hash does not match content");
        }
    }

    public List<PromptPartType> partTypes() {
        List<PromptPartType> types = new ArrayList<PromptPartType>();
        for (HarnessPromptPart part : parts) {
            types.add(part.getType());
        }
        return Collections.unmodifiableList(types);
    }
}
