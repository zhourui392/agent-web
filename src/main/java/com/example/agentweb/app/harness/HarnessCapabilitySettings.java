package com.example.agentweb.app.harness;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

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
    private final Set<String> allowedMcpServerIds;

    public HarnessCapabilitySettings(String policyVersion, String platformSafety,
                                     String environmentGuardrail) {
        this(policyVersion, platformSafety, environmentGuardrail,
                Collections.<String>emptySet());
    }

    public HarnessCapabilitySettings(String policyVersion, String platformSafety,
                                     String environmentGuardrail,
                                     Set<String> allowedMcpServerIds) {
        this.policyVersion = require(policyVersion, "policy version");
        this.platformSafety = require(platformSafety, "platform safety");
        this.environmentGuardrail = require(environmentGuardrail, "environment guardrail");
        if (allowedMcpServerIds == null || allowedMcpServerIds.contains(null)) {
            throw new IllegalArgumentException("allowed MCP server ids must not be null or contain null");
        }
        Set<String> copy = new LinkedHashSet<String>();
        for (String serverId : allowedMcpServerIds) {
            copy.add(require(serverId, "allowed MCP server id"));
        }
        this.allowedMcpServerIds = Collections.unmodifiableSet(copy);
    }

    private String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
