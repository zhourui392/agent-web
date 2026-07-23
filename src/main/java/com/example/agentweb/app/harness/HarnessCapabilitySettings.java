package com.example.agentweb.app.harness;

import lombok.Getter;

/**
 * Capability Resolver 的平台配置快照输入。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class HarnessCapabilitySettings {

    private final String policyVersion;
    private final String platformSafety;
    private final String environmentGuardrail;

    public HarnessCapabilitySettings(String policyVersion, String platformSafety,
                                     String environmentGuardrail) {
        this.policyVersion = require(policyVersion, "policy version");
        this.platformSafety = require(platformSafety, "platform safety");
        this.environmentGuardrail = require(environmentGuardrail, "environment guardrail");
    }

    private String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
