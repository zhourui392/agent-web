package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.HarnessStage;

import java.util.List;

/**
 * Harness M1 写用例端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface HarnessAppService {

    HarnessMutationResult create(CreateHarnessRunCommand command);

    HarnessMutationResult startStage(String runId, HarnessStage stage, String idempotencyKey);

    HarnessMutationResult registerArtifact(RegisterHarnessArtifactCommand command);

    HarnessMutationResult recordGate(String runId, HarnessStage stage, String rule,
                                     boolean passed, List<String> evidenceReferences,
                                     String reason);

    HarnessMutationResult evaluateGate(String runId, HarnessStage stage, String rule);

    HarnessMutationResult requestInput(String runId, HarnessStage stage, String questionId,
                                       String question, boolean blocking);

    HarnessMutationResult answerQuestion(String runId, String questionId, String answer);

    HarnessMutationResult requestApproval(String runId, HarnessStage stage);

    HarnessMutationResult approve(String runId, HarnessStage stage,
                                  String artifactBaselineHash, String reason,
                                  String idempotencyKey);

    HarnessMutationResult reject(String runId, HarnessStage stage,
                                 String artifactBaselineHash, String reason,
                                 String idempotencyKey);

    HarnessMutationResult retryStage(String runId, HarnessStage stage, String idempotencyKey);

    HarnessMutationResult approveDeployment(String runId, String inputBaselineHash,
                                            String reason, String idempotencyKey);
}
