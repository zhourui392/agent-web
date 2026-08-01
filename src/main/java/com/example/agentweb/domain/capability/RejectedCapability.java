package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 能力解析过程中被拒绝的可审计事实。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class RejectedCapability {

    private final String id;
    private final String reasonCode;

    public RejectedCapability(String id, String reasonCode) {
        this.id = DomainText.require(id, "rejected capability id", 160);
        this.reasonCode = DomainText.require(reasonCode, "rejection reason code", 120);
    }
}
