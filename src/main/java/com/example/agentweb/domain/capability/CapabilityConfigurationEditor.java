package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 修改 Capability Source Configuration 的真实管理员。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@EqualsAndHashCode
public final class CapabilityConfigurationEditor {

    private final String actorId;
    private final String actorName;

    private CapabilityConfigurationEditor(String actorId, String actorName) {
        this.actorId = DomainText.require(actorId, "capability editor actor id", 128);
        this.actorName = DomainText.require(actorName, "capability editor actor name", 256);
    }

    public static CapabilityConfigurationEditor create(String actorId, String actorName) {
        return new CapabilityConfigurationEditor(actorId, actorName);
    }
}
