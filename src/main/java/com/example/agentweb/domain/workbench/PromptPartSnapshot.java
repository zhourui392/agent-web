package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

/**
 * 最终 Prompt 中一个有界组成部分的审计 Hash，不保存 Secret 正文。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PromptPartSnapshot {

    private final String type;
    private final String source;
    private final String contentHash;
    private final int contentSize;

    private PromptPartSnapshot(String type, String source,
                               String contentHash, int contentSize) {
        this.type = DomainText.require(type, "prompt part type", 80);
        this.source = DomainText.require(source, "prompt part source", 256);
        this.contentHash = DomainText.requireSha256(
                contentHash, "prompt part content hash");
        if (contentSize < 0) {
            throw new IllegalArgumentException(
                    "prompt part content size must not be negative");
        }
        this.contentSize = contentSize;
    }

    public static PromptPartSnapshot of(String type, String source,
                                        String contentHash, int contentSize) {
        return new PromptPartSnapshot(type, source, contentHash, contentSize);
    }
}
