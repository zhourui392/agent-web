package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Published Stage 引用的不可变 Command Artifact。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@EqualsAndHashCode
public final class StageCommandReference {

    private final String identifier;
    private final String version;
    private final String contentHash;

    public StageCommandReference(String identifier, String version, String contentHash) {
        this.identifier = DomainText.require(identifier, "Stage Command identifier", 128);
        this.version = DomainText.require(version, "Stage Command version", 80);
        this.contentHash = DomainText.requireSha256(
                contentHash, "Stage Command content hash");
    }
}
