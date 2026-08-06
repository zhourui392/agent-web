package com.example.agentweb.app.capability;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

/**
 * 管理后台能力来源验证的安全发现摘要。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class CapabilityDiscoveryItem {

    private final String identifier;
    private final String version;
    private final String contentHash;
    private final String displayName;

    public CapabilityDiscoveryItem(
            String identifier, String version, String contentHash, String displayName) {
        this.identifier = DomainText.require(identifier, "capability identifier", 128);
        this.version = DomainText.require(version, "capability version", 80);
        this.contentHash = DomainText.requireSha256(
                contentHash, "capability content hash");
        this.displayName = DomainText.require(
                displayName, "capability display name");
    }
}
