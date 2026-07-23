package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * Prompt 装配清单中的不可变部件。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class HarnessPromptPart {

    private final PromptPartType type;
    private final String source;
    private final String content;
    private final String sha256;

    public HarnessPromptPart(PromptPartType type, String source, String content, String sha256) {
        if (type == null) {
            throw new IllegalArgumentException("prompt part type must not be null");
        }
        this.type = type;
        this.source = DomainText.require(source, "prompt part source", 500);
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt part content must not be blank");
        }
        this.content = content;
        this.sha256 = DomainText.requireSha256(sha256, "prompt part hash");
        if (!HarnessHashing.sha256(content).equals(this.sha256)) {
            throw new IllegalArgumentException("prompt part hash does not match content");
        }
    }

    public static HarnessPromptPart from(PromptPartType type, String source, String content) {
        return new HarnessPromptPart(type, source, content, HarnessHashing.sha256(content));
    }
}
