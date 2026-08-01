package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

/**
 * 已从可信 Catalog 读取并校验 Hash 的规则资源。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuleResource {

    private final String name;
    private final String path;
    private final String content;
    private final String contentHash;

    public RuleResource(String name, String path, String content, String contentHash) {
        this.name = DomainText.require(name, "rule resource name", 120);
        this.path = DomainText.require(path, "rule resource path", 500);
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("rule resource content must not be blank");
        }
        this.content = content;
        this.contentHash = DomainText.requireSha256(contentHash, "rule resource hash");
        if (!CanonicalHashing.sha256(content).equals(this.contentHash)) {
            throw new IllegalArgumentException("rule resource hash does not match content");
        }
    }
}
