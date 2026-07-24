package com.example.agentweb.app.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Harness Run、四阶段、证据与时间线的只读投影；不作为半截聚合参与写操作。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class HarnessRunView {

    private final String runId;
    private final String title;
    private final String workingDir;
    private final String agentType;
    private final String environment;
    private final String definitionVersion;
    private final String status;
    private final String createdBy;
    private final long createdAt;
    private final long updatedAt;
    private final long version;
    private final WorkspaceBaselineView workspaceBaseline;
    private final List<StageView> stages;
    private final List<ArtifactView> artifacts;
    private final List<GateView> gateResults;
    private final List<ApprovalView> approvals;
    private final List<QuestionView> questions;
    private final List<EventView> events;

    public HarnessRunView(String runId, String title, String workingDir, String agentType,
                          String environment, String definitionVersion, String status,
                          String createdBy, long createdAt, long updatedAt, long version,
                          List<StageView> stages, List<ArtifactView> artifacts,
                          List<GateView> gateResults, List<ApprovalView> approvals,
                          List<EventView> events) {
        this(runId, title, workingDir, agentType, environment, definitionVersion, status,
                createdBy, createdAt, updatedAt, version,
                new WorkspaceBaselineView(workingDir, "UNKNOWN",
                        "0000000000000000000000000000000000000000", false,
                        "0000000000000000000000000000000000000000000000000000000000000000", createdAt),
                stages, artifacts, gateResults, approvals,
                Collections.<QuestionView>emptyList(), events);
    }

    public HarnessRunView(String runId, String title, String workingDir, String agentType,
                          String environment, String definitionVersion, String status,
                          String createdBy, long createdAt, long updatedAt, long version,
                          WorkspaceBaselineView workspaceBaseline,
                          List<StageView> stages, List<ArtifactView> artifacts,
                          List<GateView> gateResults, List<ApprovalView> approvals,
                          List<QuestionView> questions, List<EventView> events) {
        this.runId = runId;
        this.title = title;
        this.workingDir = workingDir;
        this.agentType = agentType;
        this.environment = environment;
        this.definitionVersion = definitionVersion;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.workspaceBaseline = workspaceBaseline;
        this.stages = immutable(stages);
        this.artifacts = immutable(artifacts);
        this.gateResults = immutable(gateResults);
        this.approvals = immutable(approvals);
        this.questions = immutable(questions);
        this.events = immutable(events);
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    /** 创建时 Git 基线投影。 */
    @Getter
    public static final class WorkspaceBaselineView {
        private final String repositoryRoot;
        private final String branch;
        private final String head;
        private final boolean clean;
        private final String diffHash;
        private final long capturedAt;

        public WorkspaceBaselineView(String repositoryRoot, String branch, String head,
                                     boolean clean, String diffHash, long capturedAt) {
            this.repositoryRoot = repositoryRoot;
            this.branch = branch;
            this.head = head;
            this.clean = clean;
            this.diffHash = diffHash;
            this.capturedAt = capturedAt;
        }
    }

    /** Stage 及其不可覆盖 Attempt 的投影。 */
    @Getter
    public static final class StageView {
        private final String stage;
        private final String status;
        private final List<String> requiredOutputArtifacts;
        private final List<String> deterministicGates;
        private final String approvalType;
        private final String artifactBaselineHash;
        private final List<AttemptView> attempts;

        public StageView(String stage, String status, List<String> requiredOutputArtifacts,
                         List<String> deterministicGates, String approvalType,
                         String artifactBaselineHash, List<AttemptView> attempts) {
            this.stage = stage;
            this.status = status;
            this.requiredOutputArtifacts = immutable(requiredOutputArtifacts);
            this.deterministicGates = immutable(deterministicGates);
            this.approvalType = approvalType;
            this.artifactBaselineHash = artifactBaselineHash;
            this.attempts = immutable(attempts);
        }
    }

    /** 单次 Attempt 投影。 */
    @Getter
    public static final class AttemptView {
        private final int number;
        private final String status;
        private final long startedAt;
        private final Long finishedAt;
        private final String failureReason;

        public AttemptView(int number, String status, long startedAt,
                           Long finishedAt, String failureReason) {
            this.number = number;
            this.status = status;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.failureReason = failureReason;
        }
    }

    /** Artifact 元数据投影，正文不进入管理详情响应。 */
    @Getter
    public static final class ArtifactView {
        private final String artifactId;
        private final String artifactType;
        private final int version;
        private final String stage;
        private final int attempt;
        private final String contentType;
        private final long sizeBytes;
        private final String sha256;
        private final String classification;
        private final String createdBy;
        private final long createdAt;
        private final String sourceArtifactsJson;

        public ArtifactView(String artifactId, String artifactType, int version,
                            String stage, int attempt, String contentType, long sizeBytes,
                            String sha256, String classification, String createdBy,
                            long createdAt, String sourceArtifactsJson) {
            this.artifactId = artifactId;
            this.artifactType = artifactType;
            this.version = version;
            this.stage = stage;
            this.attempt = attempt;
            this.contentType = contentType;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.classification = classification;
            this.createdBy = createdBy;
            this.createdAt = createdAt;
            this.sourceArtifactsJson = sourceArtifactsJson;
        }
    }

    /** Gate 结果投影。 */
    @Getter
    public static final class GateView {
        private final String resultId;
        private final String stage;
        private final int attempt;
        private final String rule;
        private final boolean passed;
        private final String artifactBaselineHash;
        private final String evidenceJson;
        private final String reason;
        private final long evaluatedAt;

        public GateView(String resultId, String stage, int attempt, String rule,
                        boolean passed, String artifactBaselineHash, String evidenceJson,
                        String reason, long evaluatedAt) {
            this.resultId = resultId;
            this.stage = stage;
            this.attempt = attempt;
            this.rule = rule;
            this.passed = passed;
            this.artifactBaselineHash = artifactBaselineHash;
            this.evidenceJson = evidenceJson;
            this.reason = reason;
            this.evaluatedAt = evaluatedAt;
        }
    }

    /** Approval 历史投影。 */
    @Getter
    public static final class ApprovalView {
        private final String approvalId;
        private final String stage;
        private final int attempt;
        private final String approvalType;
        private final String decision;
        private final String artifactBaselineHash;
        private final String decidedBy;
        private final String reason;
        private final long decidedAt;
        private final boolean valid;
        private final Long invalidatedAt;

        public ApprovalView(String approvalId, String stage, int attempt, String approvalType,
                            String decision, String artifactBaselineHash, String decidedBy,
                            String reason, long decidedAt, boolean valid, Long invalidatedAt) {
            this.approvalId = approvalId;
            this.stage = stage;
            this.attempt = attempt;
            this.approvalType = approvalType;
            this.decision = decision;
            this.artifactBaselineHash = artifactBaselineHash;
            this.decidedBy = decidedBy;
            this.reason = reason;
            this.decidedAt = decidedAt;
            this.valid = valid;
            this.invalidatedAt = invalidatedAt;
        }
    }

    /** 补充输入问题及回答投影。 */
    @Getter
    public static final class QuestionView {
        private final String questionId;
        private final String stage;
        private final int attempt;
        private final String question;
        private final boolean blocking;
        private final String askedBy;
        private final long askedAt;
        private final String answer;
        private final String answeredBy;
        private final Long answeredAt;

        public QuestionView(String questionId, String stage, int attempt, String question,
                            boolean blocking, String askedBy, long askedAt, String answer,
                            String answeredBy, Long answeredAt) {
            this.questionId = questionId;
            this.stage = stage;
            this.attempt = attempt;
            this.question = question;
            this.blocking = blocking;
            this.askedBy = askedBy;
            this.askedAt = askedAt;
            this.answer = answer;
            this.answeredBy = answeredBy;
            this.answeredAt = answeredAt;
        }
    }

    /** 审计时间线投影。 */
    @Getter
    public static final class EventView {
        private final long sequence;
        private final String eventType;
        private final String stage;
        private final String actor;
        private final String detail;
        private final long occurredAt;

        public EventView(long sequence, String eventType, String stage,
                         String actor, String detail, long occurredAt) {
            this.sequence = sequence;
            this.eventType = eventType;
            this.stage = stage;
            this.actor = actor;
            this.detail = detail;
            this.occurredAt = occurredAt;
        }
    }
}
