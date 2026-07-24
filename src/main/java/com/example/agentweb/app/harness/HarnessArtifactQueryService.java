package com.example.agentweb.app.harness;

import java.util.Optional;

/**
 * Artifact 正文 CQRS 查询端口。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface HarnessArtifactQueryService {

    Optional<HarnessArtifactContentView> findLatest(String runId, String artifactId);

    Optional<HarnessArtifactContentView> findFinalReport(String runId);
}
