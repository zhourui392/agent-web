package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 一次四阶段研发交付运行的聚合根。
 *
 * <p>阶段顺序、唯一可写 Attempt、Gate、Approval Hash 绑定、重试留痕及
 * 上游变化后的下游失效均在此强一致性边界内完成。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class HarnessRun {

    private final String id;
    private final String title;
    private final String workingDir;
    private final String agentType;
    private final String environment;
    private final String definitionVersion;
    private final String createdBy;
    private final String idempotencyKey;
    private final Instant createdAt;
    private final List<StageExecution> stages;
    private final List<ArtifactDescriptor> artifacts;
    private final List<GateResult> gateResults;
    private final List<Approval> approvals;
    private final List<HarnessEvent> events;
    private HarnessRunStatus status;
    private Instant updatedAt;
    private long version;

    private HarnessRun(String id, String title, String workingDir, String agentType,
                       String environment, String definitionVersion, String createdBy,
                       String idempotencyKey, HarnessRunStatus status, Instant createdAt,
                       Instant updatedAt, long version, List<StageExecution> stages,
                       List<ArtifactDescriptor> artifacts, List<GateResult> gateResults,
                       List<Approval> approvals, List<HarnessEvent> events) {
        this.id = DomainText.require(id, "run id", 128);
        this.title = DomainText.require(title, "run title");
        this.workingDir = DomainText.require(workingDir, "working directory");
        this.agentType = DomainText.require(agentType, "agent type");
        this.environment = DomainText.require(environment, "environment");
        this.definitionVersion = DomainText.require(definitionVersion, "definition version");
        this.createdBy = DomainText.require(createdBy, "run creator", 128);
        this.idempotencyKey = DomainText.require(idempotencyKey, "run idempotency key", 128);
        if (status == null) {
            throw new IllegalArgumentException("run status must not be null");
        }
        this.status = status;
        this.createdAt = DomainText.requireTime(createdAt, "run created time");
        this.updatedAt = DomainText.requireTime(updatedAt, "run updated time");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("run updated time must not precede creation");
        }
        if (version < 0L) {
            throw new IllegalArgumentException("run version must not be negative");
        }
        this.version = version;
        this.stages = copy(stages, "stages");
        this.artifacts = copy(artifacts, "artifacts");
        this.gateResults = copy(gateResults, "gate results");
        this.approvals = copy(approvals, "approvals");
        this.events = copy(events, "events");
        validateStageSet();
        validateRestoredState();
    }

    public static HarnessRun create(String id, String title, String workingDir, String agentType,
                                    String environment, String definitionVersion, String createdBy,
                                    String idempotencyKey, List<StageContract> contracts, Instant now) {
        List<StageExecution> stageExecutions = new ArrayList<StageExecution>();
        if (contracts == null) {
            throw new IllegalArgumentException("stage contracts must not be null");
        }
        for (StageContract contract : contracts) {
            stageExecutions.add(StageExecution.pending(contract));
        }
        HarnessRun run = new HarnessRun(id, title, workingDir, agentType, environment,
                definitionVersion, createdBy, idempotencyKey, HarnessRunStatus.DRAFT,
                now, now, 0L, stageExecutions,
                Collections.<ArtifactDescriptor>emptyList(),
                Collections.<GateResult>emptyList(), Collections.<Approval>emptyList(),
                Collections.<HarnessEvent>emptyList());
        run.addEvent("RUN_CREATED", null, createdBy, definitionVersion, now);
        return run;
    }

    public static HarnessRun restore(String id, String title, String workingDir, String agentType,
                                     String environment, String definitionVersion, String createdBy,
                                     String idempotencyKey, HarnessRunStatus status, Instant createdAt,
                                     Instant updatedAt, long version, List<StageExecution> stages,
                                     List<ArtifactDescriptor> artifacts, List<GateResult> gateResults,
                                     List<Approval> approvals, List<HarnessEvent> events) {
        return new HarnessRun(id, title, workingDir, agentType, environment,
                definitionVersion, createdBy, idempotencyKey, status, createdAt, updatedAt,
                version, stages, artifacts, gateResults, approvals, events);
    }

    public List<StageExecution> getStages() {
        return Collections.unmodifiableList(stages);
    }

    public List<ArtifactDescriptor> getArtifacts() {
        return Collections.unmodifiableList(artifacts);
    }

    public List<GateResult> getGateResults() {
        return Collections.unmodifiableList(gateResults);
    }

    public List<Approval> getApprovals() {
        return Collections.unmodifiableList(approvals);
    }

    public List<HarnessEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /**
     * 按业务阶段查询聚合内实体，调用方无需遍历内部集合。
     *
     * @param stage 阶段
     * @return 阶段实体
     */
    public StageExecution stage(HarnessStage stage) {
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        for (StageExecution execution : stages) {
            if (execution.getStage() == stage) {
                return execution;
            }
        }
        throw new IllegalStateException("stage contract is missing: " + stage);
    }

    /**
     * 返回允许固化 Capability Snapshot 的当前 Attempt。
     *
     * @param stage 目标阶段
     * @return 当前运行中的 Attempt 编号
     */
    public int capabilitySnapshotAttempt(HarnessStage stage) {
        StageExecution execution = stage(stage);
        if (execution.getStatus() != StageStatus.RUNNING) {
            throw new IllegalHarnessTransitionException(
                    "capability snapshot requires a running stage: " + stage + " is " + execution.getStatus());
        }
        return execution.currentAttempt().getNumber();
    }

    public AgentRuntime capabilityRuntime() {
        return AgentRuntime.from(agentType);
    }

    public String capabilityEnvironment() {
        return environment;
    }

    public StageContract capabilityStageContract(HarnessStage stage) {
        return stage(stage).getContract();
    }

    public boolean startStage(HarnessStage stage, String commandIdempotencyKey, Instant now) {
        return startStage(stage, commandIdempotencyKey, createdBy, now);
    }

    public boolean startStage(HarnessStage stage, String commandIdempotencyKey,
                              String actor, Instant now) {
        requireMutable();
        StageExecution target = stage(stage);
        String key = DomainText.require(commandIdempotencyKey, "stage idempotency key");
        if (target.isCurrentAttempt(key)) {
            return false;
        }
        requireNoWritableAttempt();
        if (target.getStatus() != StageStatus.PENDING) {
            throw new IllegalHarnessTransitionException(
                    "stage must be pending before start: " + stage + " is " + target.getStatus());
        }
        requirePreviousPassed(stage);
        target.startNewAttempt(key, requireTime(now));
        status = HarnessRunStatus.ACTIVE;
        touch(now);
        addEvent("STAGE_STARTED", stage, actor,
                "attempt=" + target.currentAttempt().getNumber(), now);
        return true;
    }

    public boolean retryStage(HarnessStage stage, String commandIdempotencyKey, Instant now) {
        return retryStage(stage, commandIdempotencyKey, createdBy, now);
    }

    public boolean retryStage(HarnessStage stage, String commandIdempotencyKey,
                              String actor, Instant now) {
        requireMutable();
        StageExecution target = stage(stage);
        String key = DomainText.require(commandIdempotencyKey, "retry idempotency key");
        if (target.isCurrentAttempt(key)) {
            return false;
        }
        requireNoWritableAttempt();
        if (target.getStatus() != StageStatus.FAILED
                && target.getStatus() != StageStatus.PASSED
                && target.getStatus() != StageStatus.INVALIDATED) {
            throw new IllegalHarnessTransitionException(
                    "stage cannot be retried from " + target.getStatus());
        }
        requirePreviousPassed(stage);
        invalidateApprovalsFrom(stage, now);
        invalidateDownstream(stage, now);
        target.startNewAttempt(key, requireTime(now));
        status = HarnessRunStatus.ACTIVE;
        touch(now);
        addEvent("STAGE_RETRIED", stage, actor,
                "attempt=" + target.currentAttempt().getNumber(), now);
        return true;
    }

    public ArtifactDescriptor registerArtifact(HarnessStage stage, String artifactId,
                                               ArtifactType artifactType, ArtifactContent content,
                                               String contentType,
                                               ArtifactClassification classification,
                                               String actor,
                                               List<ArtifactReference> sourceArtifacts,
                                               Instant now) {
        requireMutable();
        StageExecution execution = stage(stage);
        requireWritableStage(execution);
        if (content == null) {
            throw new IllegalArgumentException("artifact content must not be null");
        }
        if (!execution.getContract().getRequiredOutputArtifacts().contains(artifactType)) {
            throw new IllegalHarnessTransitionException(
                    "artifact type is not an output of " + stage + ": " + artifactType);
        }
        String logicalArtifactId = logicalArtifactId(stage, artifactType, artifactId);
        int versionNumber = nextArtifactVersion(logicalArtifactId);
        ArtifactDescriptor descriptor = new ArtifactDescriptor(
                logicalArtifactId, artifactType, versionNumber, id, stage,
                execution.currentAttempt().getNumber(), contentType, content.getSizeBytes(),
                content.getSha256(), classification, actor, requireTime(now), sourceArtifacts);
        artifacts.add(descriptor);
        if (execution.getStatus() == StageStatus.WAITING_APPROVAL) {
            execution.resumeAfterRejection();
            status = HarnessRunStatus.ACTIVE;
        }
        invalidateApprovalsFrom(stage, now);
        touch(now);
        addEvent("ARTIFACT_REGISTERED", stage, actor,
                artifactType.name() + "@" + versionNumber + ":" + descriptor.getSha256(), now);
        return descriptor;
    }

    public List<ArtifactDescriptor> artifactVersions(ArtifactType artifactType) {
        List<ArtifactDescriptor> versions = new ArrayList<ArtifactDescriptor>();
        for (ArtifactDescriptor descriptor : artifacts) {
            if (descriptor.getArtifactType() == artifactType) {
                versions.add(descriptor);
            }
        }
        versions.sort(Comparator.comparingInt(ArtifactDescriptor::getVersion));
        return Collections.unmodifiableList(versions);
    }

    public String currentArtifactBaselineHash(HarnessStage stage) {
        StageExecution execution = stage(stage);
        if (execution.getAttempts().isEmpty()) {
            throw new IllegalHarnessTransitionException("stage has no artifact baseline: " + stage);
        }
        int attempt = execution.currentAttempt().getNumber();
        List<ArtifactDescriptor> current = currentArtifacts(stage, attempt);
        if (current.isEmpty()) {
            throw new IllegalHarnessTransitionException("stage has no artifact baseline: " + stage);
        }
        current.sort(Comparator
                .comparing((ArtifactDescriptor item) -> item.getArtifactType().name())
                .thenComparing(ArtifactDescriptor::getArtifactId)
                .thenComparingInt(ArtifactDescriptor::getVersion));
        StringBuilder canonical = new StringBuilder();
        for (ArtifactDescriptor descriptor : current) {
            canonical.append(descriptor.getArtifactType().name()).append(':')
                    .append(descriptor.getArtifactId()).append(':')
                    .append(descriptor.getVersion()).append(':')
                    .append(descriptor.getSha256()).append('\n');
        }
        return ArtifactContent.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public boolean recordGate(HarnessStage stage, String resultId, String rule, boolean passed,
                              List<String> evidenceReferences, String reason, Instant now) {
        return recordGate(stage, resultId, rule, passed, evidenceReferences, reason, createdBy, now);
    }

    public boolean recordGate(HarnessStage stage, String resultId, String rule, boolean passed,
                              List<String> evidenceReferences, String reason,
                              String actor, Instant now) {
        String normalizedResultId = DomainText.require(resultId, "gate result id");
        if (hasGateResult(normalizedResultId)) {
            return false;
        }
        requireMutable();
        StageExecution execution = stage(stage);
        if (execution.getStatus() != StageStatus.RUNNING) {
            throw new IllegalHarnessTransitionException(
                    "gate can only run for a running stage: " + stage);
        }
        String normalizedRule = DomainText.require(rule, "gate rule");
        if (!execution.getContract().getDeterministicGates().contains(normalizedRule)) {
            throw new IllegalHarnessTransitionException(
                    "gate rule is not in stage contract: " + normalizedRule);
        }
        GateResult result = new GateResult(normalizedResultId, stage,
                execution.currentAttempt().getNumber(), normalizedRule, passed,
                currentArtifactBaselineHash(stage), evidenceReferences, reason, requireTime(now));
        gateResults.add(result);
        if (!passed) {
            execution.fail(reason, now);
            status = HarnessRunStatus.FAILED;
        }
        touch(now);
        addEvent(passed ? "GATE_PASSED" : "GATE_FAILED", stage, actor,
                normalizedRule, now);
        return true;
    }

    public void submitForApproval(HarnessStage stage, Instant now) {
        submitForApproval(stage, createdBy, now);
    }

    public void submitForApproval(HarnessStage stage, String actor, Instant now) {
        requireMutable();
        StageExecution execution = stage(stage);
        if (execution.getStatus() != StageStatus.RUNNING) {
            throw new IllegalHarnessTransitionException(
                    "only a running stage can request approval: " + stage);
        }
        String baselineHash = currentArtifactBaselineHash(stage);
        requireAllOutputArtifacts(execution);
        requireAllGatesPassed(execution, baselineHash);
        execution.waitForApproval();
        status = HarnessRunStatus.WAITING_APPROVAL;
        touch(now);
        addEvent("APPROVAL_REQUESTED", stage, actor, baselineHash, now);
    }

    public boolean approve(HarnessStage stage, String approvalId, String artifactBaselineHash,
                           String actor, String reason, Instant now) {
        return decide(stage, approvalId, artifactBaselineHash, actor, reason,
                ApprovalDecision.APPROVED, now);
    }

    public boolean reject(HarnessStage stage, String approvalId, String artifactBaselineHash,
                          String actor, String reason, Instant now) {
        return decide(stage, approvalId, artifactBaselineHash, actor, reason,
                ApprovalDecision.REJECTED, now);
    }

    public boolean cancel(String actor, String reason, Instant now) {
        if (status == HarnessRunStatus.CANCELLED) {
            return false;
        }
        requireMutable();
        String normalizedActor = DomainText.require(actor, "cancellation actor");
        String normalizedReason = DomainText.require(reason, "cancellation reason");
        Instant transitionTime = requireTime(now);
        for (StageExecution execution : stages) {
            execution.cancel(transitionTime);
        }
        invalidateApprovalsFrom(HarnessStage.ANALYSIS, transitionTime);
        status = HarnessRunStatus.CANCELLED;
        touch(transitionTime);
        addEvent("RUN_CANCELLED", null, normalizedActor, normalizedReason, transitionTime);
        return true;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * 判断幂等创建请求是否与既有 Run 的业务基线相同，避免 Application 用 getter 重组规则。
     *
     * @param requestedTitle 标题
     * @param requestedWorkingDir 已授权的真实工作目录
     * @param requestedAgentType Agent 类型
     * @param requestedEnvironment 目标环境
     * @param requestedDefinitionVersion Definition 版本
     * @return 是否为同一个创建请求
     */
    public boolean matchesCreation(String requestedTitle, String requestedWorkingDir,
                                   String requestedAgentType, String requestedEnvironment,
                                   String requestedDefinitionVersion) {
        return title.equals(DomainText.require(requestedTitle, "run title"))
                && workingDir.equals(DomainText.require(requestedWorkingDir, "working directory"))
                && agentType.equals(DomainText.require(requestedAgentType, "agent type"))
                && environment.equals(DomainText.require(requestedEnvironment, "environment"))
                && definitionVersion.equals(DomainText.require(
                        requestedDefinitionVersion, "definition version"));
    }

    public void requireMatchingCreation(String requestedTitle, String requestedWorkingDir,
                                        String requestedAgentType, String requestedEnvironment,
                                        String requestedDefinitionVersion) {
        if (!matchesCreation(requestedTitle, requestedWorkingDir, requestedAgentType,
                requestedEnvironment, requestedDefinitionVersion)) {
            throw new DuplicateHarnessRunException(createdBy, idempotencyKey);
        }
    }

    public void synchronizeVersion(long persistedVersion) {
        if (persistedVersion < version) {
            throw new IllegalArgumentException("persisted version must not move backwards");
        }
        version = persistedVersion;
    }

    private boolean decide(HarnessStage stage, String approvalId, String artifactBaselineHash,
                           String actor, String reason, ApprovalDecision decision, Instant now) {
        String normalizedApprovalId = DomainText.require(approvalId, "approval id");
        if (hasApproval(normalizedApprovalId)) {
            return false;
        }
        requireMutable();
        StageExecution execution = stage(stage);
        if (execution.getStatus() != StageStatus.WAITING_APPROVAL) {
            throw new IllegalHarnessTransitionException(
                    "stage is not waiting for approval: " + stage);
        }
        String requestedHash = DomainText.requireSha256(
                artifactBaselineHash, "approval artifact baseline hash");
        String currentHash = currentArtifactBaselineHash(stage);
        if (!currentHash.equals(requestedHash)) {
            throw new IllegalHarnessTransitionException(
                    "approval artifact baseline hash is stale for " + stage);
        }
        Instant transitionTime = requireTime(now);
        Approval approval = Approval.decide(normalizedApprovalId, execution, decision,
                requestedHash, actor, reason, transitionTime);
        approvals.add(approval);
        if (decision == ApprovalDecision.APPROVED) {
            execution.pass(transitionTime);
            status = allStagesPassed() ? HarnessRunStatus.COMPLETED : HarnessRunStatus.ACTIVE;
            addEvent("STAGE_APPROVED", stage, actor, requestedHash, transitionTime);
        } else {
            execution.resumeAfterRejection();
            status = HarnessRunStatus.ACTIVE;
            addEvent("STAGE_REJECTED", stage, actor, reason, transitionTime);
        }
        touch(transitionTime);
        return true;
    }

    private void requireAllOutputArtifacts(StageExecution execution) {
        Set<ArtifactType> present = EnumSet.noneOf(ArtifactType.class);
        int currentAttempt = execution.currentAttempt().getNumber();
        for (ArtifactDescriptor descriptor : artifacts) {
            if (descriptor.getStage() == execution.getStage()
                    && descriptor.getAttempt() == currentAttempt) {
                present.add(descriptor.getArtifactType());
            }
        }
        if (!present.containsAll(execution.getContract().getRequiredOutputArtifacts())) {
            Set<ArtifactType> missing = EnumSet.copyOf(execution.getContract().getRequiredOutputArtifacts());
            missing.removeAll(present);
            throw new IllegalHarnessTransitionException("required artifacts are missing: " + missing);
        }
    }

    private void requireAllGatesPassed(StageExecution execution, String baselineHash) {
        Set<String> passed = new java.util.HashSet<String>();
        int attempt = execution.currentAttempt().getNumber();
        for (GateResult result : gateResults) {
            if (result.getStage() == execution.getStage()
                    && result.getAttempt() == attempt
                    && result.isPassed()
                    && result.getArtifactBaselineHash().equals(baselineHash)) {
                passed.add(result.getRule());
            }
        }
        if (!passed.containsAll(execution.getContract().getDeterministicGates())) {
            Set<String> missing = new java.util.LinkedHashSet<String>(
                    execution.getContract().getDeterministicGates());
            missing.removeAll(passed);
            throw new IllegalHarnessTransitionException("deterministic gates are missing: " + missing);
        }
    }

    private void requireNoWritableAttempt() {
        for (StageExecution execution : stages) {
            if (execution.getStatus().isWritable()) {
                throw new IllegalHarnessTransitionException(
                        "run already has a writable attempt: " + execution.getStage());
            }
        }
    }

    private void requirePreviousPassed(HarnessStage target) {
        if (target.hasPrevious() && stage(target.previous()).getStatus() != StageStatus.PASSED) {
            throw new IllegalHarnessTransitionException(
                    "previous stage must pass before starting " + target);
        }
    }

    private void requireWritableStage(StageExecution execution) {
        if (execution.getStatus() != StageStatus.RUNNING
                && execution.getStatus() != StageStatus.WAITING_APPROVAL) {
            throw new IllegalHarnessTransitionException(
                    "stage is not writable: " + execution.getStage());
        }
    }

    private String logicalArtifactId(HarnessStage stage, ArtifactType type, String suggestedId) {
        for (ArtifactDescriptor descriptor : artifacts) {
            if (descriptor.getStage() == stage && descriptor.getArtifactType() == type) {
                return descriptor.getArtifactId();
            }
        }
        return DomainText.require(suggestedId, "artifact id", 128);
    }

    private int nextArtifactVersion(String artifactId) {
        int max = 0;
        for (ArtifactDescriptor descriptor : artifacts) {
            if (descriptor.getArtifactId().equals(artifactId)) {
                max = Math.max(max, descriptor.getVersion());
            }
        }
        return max + 1;
    }

    private List<ArtifactDescriptor> currentArtifacts(HarnessStage target, int attempt) {
        java.util.Map<ArtifactType, ArtifactDescriptor> latest =
                new java.util.EnumMap<ArtifactType, ArtifactDescriptor>(ArtifactType.class);
        for (ArtifactDescriptor descriptor : artifacts) {
            if (descriptor.getStage() == target && descriptor.getAttempt() == attempt) {
                ArtifactDescriptor previous = latest.get(descriptor.getArtifactType());
                if (previous == null || descriptor.getVersion() > previous.getVersion()) {
                    latest.put(descriptor.getArtifactType(), descriptor);
                }
            }
        }
        return new ArrayList<ArtifactDescriptor>(latest.values());
    }

    private boolean hasGateResult(String resultId) {
        for (GateResult result : gateResults) {
            if (result.getResultId().equals(resultId)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasApproval(String approvalId) {
        for (Approval approval : approvals) {
            if (approval.getApprovalId().equals(approvalId)) {
                return true;
            }
        }
        return false;
    }

    private void invalidateApprovalsFrom(HarnessStage stage, Instant now) {
        for (Approval approval : approvals) {
            if (approval.getStage().ordinal() >= stage.ordinal()) {
                approval.invalidate(now);
            }
        }
    }

    private void invalidateDownstream(HarnessStage changedStage, Instant now) {
        for (StageExecution execution : stages) {
            if (execution.getStage().ordinal() > changedStage.ordinal() && execution.invalidate()) {
                addEvent("STAGE_INVALIDATED", execution.getStage(), createdBy,
                        "upstream=" + changedStage, now);
            }
        }
    }

    private boolean allStagesPassed() {
        for (StageExecution execution : stages) {
            if (execution.getStatus() != StageStatus.PASSED) {
                return false;
            }
        }
        return true;
    }

    private void requireMutable() {
        if (status.isTerminal()) {
            throw new IllegalHarnessTransitionException(
                    "terminal run cannot execute ordinary actions: " + status);
        }
    }

    private Instant requireTime(Instant now) {
        Instant value = DomainText.requireTime(now, "run transition time");
        if (value.isBefore(updatedAt)) {
            throw new IllegalArgumentException("run transition time must not move backwards");
        }
        return value;
    }

    private void touch(Instant now) {
        updatedAt = requireTime(now);
    }

    private void addEvent(String eventType, HarnessStage stage, String actor,
                          String detail, Instant now) {
        events.add(new HarnessEvent(events.size() + 1L, eventType, stage,
                actor, detail, now));
    }

    private void validateStageSet() {
        if (stages.size() != HarnessStage.values().length) {
            throw new IllegalArgumentException("run must contain exactly four stage contracts");
        }
        for (HarnessStage stage : HarnessStage.values()) {
            StageExecution execution = stages.get(stage.ordinal());
            if (execution == null || execution.getStage() != stage) {
                throw new IllegalArgumentException("stage contracts must use fixed order: " + stage);
            }
        }
    }

    private void validateRestoredState() {
        int writable = 0;
        for (StageExecution execution : stages) {
            if (execution.getStatus().isWritable()) {
                writable++;
            }
        }
        if (writable > 1) {
            throw new IllegalArgumentException("run must have at most one writable attempt");
        }
        if (status == HarnessRunStatus.COMPLETED && !allStagesPassed()) {
            throw new IllegalArgumentException("completed run requires all stages passed");
        }
    }

    private static <T> List<T> copy(List<T> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return new ArrayList<T>(values);
    }
}
