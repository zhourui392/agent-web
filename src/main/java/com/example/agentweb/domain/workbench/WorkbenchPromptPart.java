package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.nio.charset.StandardCharsets;

/**
 * Workbench Prompt 的一个不可变正文部件。
 *
 * <p>正文不做 trim，保证最终交给 Runtime 的字节、Hash 与 Snapshot 完全一致。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchPromptPart {

    private final WorkbenchPromptPartType type;
    private final String source;
    private final String content;
    private final String contentHash;
    private final int contentSize;

    private WorkbenchPromptPart(
            WorkbenchPromptPartType type, String source,
            String content, String contentHash) {
        if (type == null) {
            throw new IllegalArgumentException(
                    "workbench prompt part type must not be null");
        }
        this.type = type;
        this.source = DomainText.require(
                source, "workbench prompt part source", 256);
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "workbench prompt part content must not be blank");
        }
        this.content = content;
        this.contentHash = DomainText.requireSha256(
                contentHash, "workbench prompt part content hash");
        if (!CanonicalHashing.sha256(content).equals(this.contentHash)) {
            throw new IllegalArgumentException(
                    "workbench prompt part hash does not match content");
        }
        this.contentSize = content.getBytes(StandardCharsets.UTF_8).length;
    }

    public static WorkbenchPromptPart of(
            WorkbenchPromptPartType type, String source, String content) {
        if (content == null) {
            throw new IllegalArgumentException(
                    "workbench prompt part content must not be null");
        }
        return new WorkbenchPromptPart(
                type, source, content, CanonicalHashing.sha256(content));
    }

    public static WorkbenchPromptPart restore(
            WorkbenchPromptPartType type, String source,
            String content, String contentHash) {
        return new WorkbenchPromptPart(type, source, content, contentHash);
    }

    public PromptPartSnapshot snapshot() {
        return PromptPartSnapshot.of(
                type.name(), source, contentHash, contentSize);
    }
}
