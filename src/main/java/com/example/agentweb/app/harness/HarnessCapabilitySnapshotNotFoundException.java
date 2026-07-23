package com.example.agentweb.app.harness;

/**
 * Capability Snapshot 读模型不存在。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public class HarnessCapabilitySnapshotNotFoundException extends RuntimeException {

    public HarnessCapabilitySnapshotNotFoundException(String runId, String stage, int attemptNumber) {
        super("Capability Snapshot not found: " + runId + "/" + stage + "/" + attemptNumber);
    }
}
