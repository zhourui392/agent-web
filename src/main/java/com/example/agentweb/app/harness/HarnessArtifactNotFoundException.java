package com.example.agentweb.app.harness;

/**
 * Run 内 Artifact 正文不存在。
 *
 * @author alex
 * @since 2026-07-23
 */
public class HarnessArtifactNotFoundException extends RuntimeException {

    public HarnessArtifactNotFoundException(String runId, String artifact) {
        super("Harness Artifact not found: " + runId + "/" + artifact);
    }
}
