package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;

/**
 * Infrastructure 重新检查目标后的不可变事实证明；不包含命令或凭据。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HighImpactPreflightProof {

    private final String requestedPayloadHash;
    private final String observedStateBinding;
    private final String preflightHash;
    private final Instant verifiedAt;

    private HighImpactPreflightProof(String requestedPayloadHash,
                                     String observedStateBinding,
                                     String preflightHash, Instant verifiedAt) {
        this.requestedPayloadHash = DomainText.requireSha256(
                requestedPayloadHash, "preflight requested payload hash");
        this.observedStateBinding = DomainText.require(
                observedStateBinding, "preflight observed state binding", 256);
        this.preflightHash = DomainText.requireSha256(
                preflightHash, "preflight hash");
        this.verifiedAt = DomainText.requireTime(verifiedAt, "preflight verified at");
    }

    public static HighImpactPreflightProof verified(
            String requestedPayloadHash, String observedStateBinding,
            String preflightHash, Instant verifiedAt) {
        return new HighImpactPreflightProof(
                requestedPayloadHash, observedStateBinding, preflightHash, verifiedAt);
    }
}
