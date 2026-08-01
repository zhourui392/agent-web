package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

/**
 * Phase Profile 中一项 required/optional 能力引用。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityReference {

    private final String id;
    private final PhaseCapabilityType type;
    private final boolean required;

    public PhaseCapabilityReference(
            String id, PhaseCapabilityType type, boolean required) {
        this.id = DomainText.require(id, "phase capability id", 160);
        if (type == null) {
            throw new IllegalArgumentException(
                    "phase capability type must not be null");
        }
        this.type = type;
        this.required = required;
    }
}
