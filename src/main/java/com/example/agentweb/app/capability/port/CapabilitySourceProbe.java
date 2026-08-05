package com.example.agentweb.app.capability.port;

import com.example.agentweb.app.capability.CapabilitySourceCandidate;
import com.example.agentweb.app.capability.CapabilitySourceProbeResult;

/**
 * Capability 来源真实路径与内容探测端口。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface CapabilitySourceProbe {

    CapabilitySourceProbeResult probe(CapabilitySourceCandidate candidate);
}
