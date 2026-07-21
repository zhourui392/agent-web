package com.example.agentweb.app.verification;

import com.example.agentweb.adapter.verification.CollectedArtifact;

import java.time.Instant;
import java.util.List;

/**
 * 验证工件落库(requirement_artifact 表)。接口放 app、infra 实现(守 ArchUnit A4)。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface ArtifactStore {

    void saveAll(String requirementId, List<CollectedArtifact> artifacts, Instant createdAt);

    List<CollectedArtifact> findByRequirementId(String requirementId);
}
