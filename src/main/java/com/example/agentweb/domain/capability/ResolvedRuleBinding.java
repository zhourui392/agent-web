package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 一条已解析且可审计的 Rule 绑定。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class ResolvedRuleBinding {

    private final String id;
    private final String version;
    private final String source;
    private final String contentHash;
    private final boolean mandatory;
    private final String safeSummary;

    public ResolvedRuleBinding(String id, String version, String source, String contentHash,
                               boolean mandatory, String safeSummary) {
        this.id = DomainText.require(id, "rule id", 160);
        this.version = DomainText.require(version, "rule version", 80);
        this.source = DomainText.require(source, "rule source", 120);
        this.contentHash = DomainText.requireSha256(contentHash, "rule content hash");
        this.mandatory = mandatory;
        this.safeSummary = DomainText.require(safeSummary, "rule safe summary", 500);
    }
}
