package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

/**
 * 单次 Workbench Run 实际展开的不可变 Command Binding。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class ResolvedCommandBinding {

    private final String identifier;
    private final String version;
    private final String contentHash;
    private final String expandedPrompt;
    private final String expandedPromptHash;

    ResolvedCommandBinding(
            String identifier, String version, String contentHash, String expandedPrompt) {
        this.identifier = DomainText.require(identifier, "command identifier", 128);
        this.version = DomainText.require(version, "command version", 80);
        this.contentHash = DomainText.requireSha256(contentHash, "command content hash");
        this.expandedPrompt = DomainText.require(
                expandedPrompt, "expanded command prompt",
                CommandDefinition.MAX_EXPANDED_PROMPT_LENGTH);
        this.expandedPromptHash = CanonicalHashing.sha256(this.expandedPrompt);
    }

    public static ResolvedCommandBinding restore(
            String identifier, String version, String contentHash,
            String expandedPrompt, String expectedExpandedPromptHash) {
        ResolvedCommandBinding restored = new ResolvedCommandBinding(
                identifier, version, contentHash, expandedPrompt);
        String expected = DomainText.requireSha256(
                expectedExpandedPromptHash,
                "expected expanded Command prompt Hash");
        if (!restored.expandedPromptHash.equals(expected)) {
            throw new IllegalArgumentException(
                    "persisted expanded Command prompt Hash does not match content");
        }
        return restored;
    }
}
