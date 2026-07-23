package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * 已读取并计算 Hash 的 Prompt 资源。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class PromptPackResource {

    private final PromptResourceRole role;
    private final String path;
    private final String content;
    private final String sha256;

    public PromptPackResource(PromptResourceRole role, String path, String content, String sha256) {
        if (role == null) {
            throw new IllegalArgumentException("prompt resource role must not be null");
        }
        this.role = role;
        this.path = DomainText.require(path, "prompt resource path", 500);
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt resource content must not be blank");
        }
        this.content = content;
        this.sha256 = DomainText.requireSha256(sha256, "prompt resource hash");
        if (!HarnessHashing.sha256(content).equals(this.sha256)) {
            throw new IllegalArgumentException("prompt resource hash does not match content");
        }
    }
}
