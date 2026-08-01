package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import lombok.Getter;

/**
 * Override 保存结果，明确只从下一轮 Run 生效。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityOverrideSaveResult {

    private final PhaseCapabilityOverrideView override;
    private final CapabilityOverrideEffectiveFrom effectiveFrom;

    private PhaseCapabilityOverrideSaveResult(
            PhaseCapabilityConfiguration configuration) {
        this.override = PhaseCapabilityOverrideView.from(configuration);
        this.effectiveFrom = CapabilityOverrideEffectiveFrom.NEXT_RUN;
    }

    public static PhaseCapabilityOverrideSaveResult saved(
            PhaseCapabilityConfiguration configuration) {
        return new PhaseCapabilityOverrideSaveResult(configuration);
    }
}
