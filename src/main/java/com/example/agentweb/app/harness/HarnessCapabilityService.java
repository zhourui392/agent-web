package com.example.agentweb.app.harness;

/**
 * Capability Snapshot 固化用例。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface HarnessCapabilityService {

    CapabilitySnapshotView resolve(ResolveHarnessCapabilityCommand command);
}
