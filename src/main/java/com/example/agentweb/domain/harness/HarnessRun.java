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
    private final WorkspaceBaseline workspaceBaseline;
    private final List<StageExecution> stages;
    private final List<ArtifactDescriptor> artifacts;
    private final List<GateResult> gateResults;
    private final List<Approval> approvals;
    private final List<HarnessQuestion> questions;
    private final List<HarnessEvent> events;
    private HarnessRunStatus status;
    private Instant updatedAt;
    private long version;

    private HarnessRun(String id, String title, String workingDir, String agentType,
                       String environment, String definitionVersion, String createdBy,
                       String idempotencyKey, HarnessRunStatus status, Instant createdAt,
                       Instant updatedAt, long version, WorkspaceBaseline workspaceBaseline,
                       List<StageExecution> stages,
                       List<ArtifactDescriptor> artifacts, List<GateResult> gateResults,
                       List<Approval> approvals, List<HarnessQuestion> questions,
                       List<HarnessEvent> events) {
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
        if (workspaceBaseline == null) {
            throw new IllegalArgumentException("workspace baseline must not be null");
        }
        this.workspaceBaseline = workspaceBaseline;
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
        this.questions = copy(questions, "questions");
        this.events = copy(events, "events");
        validateStageSet();
        validateRestoredState();
    }

    public static HarnessRun create(String id, String title, String workingDir, String agentType,
                                    String environment, String definitionVersion, String createdBy,
                                    String idempotencyKey, List<StageContract> contracts, Instant now) {
        return create(id, title, workingDir, agentType, environment, definitionVersion,
                createdBy, idempotencyKey, WorkspaceBaseline.legacy(workingDir, now), contracts, now);
    }

    public static HarnessRun create(String id, String title, String workingDir, String agentType,
                                    String environment, String definitionVersion, String createdBy,
                                    String idempotencyKey, WorkspaceBaseline workspaceBaseline,
                                    List<StageContract> contracts, Instant now) {
        List<StageExecution> stageExecutions = new ArrayList<StageExecution>();
        if (contracts == null) {
            throw new IllegalArgumentException("stage contracts must not be null");
        }
        for (StageContract contract : contracts) {
            stageExecutions.add(StageExecution.pending(contract));
        }
        HarnessRun run = new HarnessRun(id, title, workingDir, agentType, environment,
                definitionVersion, createdBy, idempotencyKey, HarnessRunStatus.DRAFT,
                now, now, 0L, workspaceBaseline, stageExecutions,
                Collections.<ArtifactDescriptor>emptyList(),
                Collections.<GateResult>emptyList(), Collections.<Approval>emptyList(),
                Collections.<HarnessQuestion>emptyList(),
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
        return restore(id, title, workingDir, agentType, environment, definitionVersion,
                createdBy, idempotencyKey, status, createdAt, updatedAt, version, stages,
                artifacts, gateResults, approvals, Collections.<HarnessQuestion>emptyList(),
                WorkspaceBaseline.legacy(workingDir, createdAt), events);
    }

    public static HarnessRun restore(String id, String title, String workingDir, String agentType,
                                     String environment, String definitionVersion, String createdBy,
                                     String idempotencyKey, HarnessRunStatus status, Instant createdAt,
                                     Instant updatedAt, long version, List<StageExecution> stages,
                                     List<ArtifactDescriptor> artifacts, List<GateResult> gateResults,
                                     List<Approval> approvals, List<HarnessQuestion> questions,
                                     List<HarnessEvent> events) {
        return restore(id, title, workingDir, agentType, environment, definitionVersion,
                createdBy, idempotencyKey, status, createdAt, updatedAt, version, stages,
                artifacts, gateResults, approvals, questions,
                WorkspaceBaseline.legacy(workingDir, createdAt), events);
    }

    public static HarnessRun restore(String id, String title, String workingDir, String agentType,
                                     String environment, String definitionVersion, String createdBy,
                                     String idempotencyKey, HarnessRunStatus status, Instant createdAt,
                                     Instant updatedAt, long version, List<StageExecution> stages,
                                     List<ArtifactDescriptor> artifacts, List<GateResult> gateResults,
                                     List<Approval> approvals, List<HarnessQuestion> questions,
                                     WorkspaceBaseline workspaceBaseline,
                                     List<HarnessEvent> events) {
        return new HarnessRun(id, title, workingDir, agentType, environment,
                definitionVersion, createdBy, idempotencyKey, status, createdAt, updatedAt,
                version, workspaceBaseline, stages, artifacts, gateResults, approvals, questions, events);
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

    public List<HarnessQuestion> getQuestions() {
        return Collections.unmodifiableList(questions);
    }

    /**
     * Run 创建事务内登记原始需求，后续 ANALYSIS Attempt 只消费该不可变版本。
     */
    public ArtifactDescriptor registerOriginalRequirement(String artifactId,
                                                          ArtifactContent content,
                                                          String actor, Instant now) {
        requireMutable();
        if (status != HarnessRunStatus.DRAFT || content == null
                || !artifactVersions(ArtifactType.ORIGINAL_REQUIREMENT).isEmpty()) {
            throw new IllegalHarnessTransitionException(
                    "original requirement can only be registered once while run is draft");
        }
        ArtifactDescriptor descriptor = new ArtifactDescriptor(
                DomainText.require(artifactId, "original requirement artifact id", 128),
                ArtifactType.ORIGINAL_REQUIREMENT, 1, id, HarnessStage.ANALYSIS, 1,
                "text/markdown", content.getSizeBytes(), content.getSha256(),
                ArtifactClassification.INTERNAL, actor, requireTime(now),
                Collections.<ArtifactReference>emptyList());
        artifacts.add(descriptor);
        touch(now);
        addEvent("ORIGINAL_REQUIREMENT_REGISTERED", HarnessStage.ANALYSIS, actor,
                descriptor.getSha256(), now);
        return descriptor;
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

    /**
     * 校验 Snapshot 是否属于当前 Attempt，并把不可变 Snapshot Hash 收回聚合边界。
     *
     * @param stage 目标阶段
     * @param snapshotReference Snapshot 引用
     * @return 可创建 RuntimeExecution 的领域许可
     */
    public ExecutionPermit authorizeExecution(HarnessStage stage,
                                              CapabilitySnapshotReference snapshotReference) {
        requireMutable();
        if (snapshotReference == null) {
            throw new IllegalArgumentException("capability snapshot reference must not be null");
        }
        StageExecution execution = stage(stage);
        StageAttempt attempt = execution.currentAttempt();
        if (execution.getStatus() != StageStatus.RUNNING
                || !id.equals(snapshotReference.getRunId())
                || stage != snapshotReference.getStage()
                || attempt.getNumber() != snapshotReference.getAttemptNumber()) {
            throw new IllegalHarnessTransitionException(
                    "capability snapshot does not belong to the current attempt");
        }
        if (attempt.getExecutionId() != null) {
            throw new IllegalHarnessTransitionException(
                    "current attempt already binds a runtime execution");
        }
        execution.bindSnapshot(snapshotReference.getSnapshotHash());
        return new ExecutionPermit(id, stage, attempt.getNumber(),
                snapshotReference.getSnapshotHash(), snapshotReference.getPromptHash(),
                snapshotReference.getSelectedMcpServerIds());
    }

    /**
     * 把独立 RuntimeExecution 引用绑定到当前 Attempt；一个 Attempt 只允许一次外部执行。
     *
     * @param reference Execution 引用
     * @param now 绑定时间
     */
    public void bindExecution(ExecutionReference reference, Instant now) {
        requireMutable();
        if (reference == null) {
            throw new IllegalArgumentException("execution reference must not be null");
        }
        StageExecution execution = stage(reference.getStage());
        StageAttempt attempt = execution.currentAttempt();
        if (!id.equals(reference.getRunId())
                || attempt.getNumber() != reference.getAttemptNumber()
                || attempt.getSnapshotHash() == null
                || !attempt.getSnapshotHash().equals(reference.getSnapshotHash())) {
            throw new IllegalHarnessTransitionException(
                    "runtime execution does not match the current snapshot attempt");
        }
        execution.bindExecution(reference.getExecutionId());
        touch(now);
        addEvent("RUNTIME_EXECUTION_BOUND", reference.getStage(), createdBy,
                reference.getExecutionId(), now);
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

    /**
     * 将用户对当前阶段的自然语言输入收回聚合，并准备唯一可写 Attempt。
     *
     * <p>首次消息启动待处理阶段；已完成 Runtime 或已批准 Attempt 的修改会创建新 Attempt，
     * 保留旧执行与 Artifact 作为审计历史。仍在运行的 Runtime 和等待问题回答的 Attempt
     * 不允许并发修订。</p>
     *
     * @param stage 当前交付阶段
     * @param idempotencyKey API 幂等键
     * @param message 用户修改指令
     * @param actor 操作者
     * @param now 操作时间
     * @return 对话准备结果
     */
    public StageConversationTurn prepareConversationTurn(HarnessStage stage,
                                                          String idempotencyKey,
                                                          String message,
                                                          String actor,
                                                          Instant now) {
        String commandId = HarnessCommandId.conversation(id, stage, idempotencyKey);
        StageConversationMessage requested = new StageConversationMessage(commandId, 1, message);
        StageConversationMessage existing = conversationMessage(stage, commandId);
        if (existing != null) {
            if (!existing.getContent().equals(requested.getContent())) {
                throw new IllegalHarnessTransitionException(
                        "conversation idempotency key belongs to a different message");
            }
            return StageConversationTurn.duplicated(existing.getAttemptNumber());
        }

        requireMutable();
        StageExecution target = stage(stage);
        Instant transitionTime = requireTime(now);
        boolean attemptOpened = prepareConversationAttempt(target, commandId, transitionTime);
        int attempt = target.currentAttempt().getNumber();
        invalidateApprovalsFrom(stage, transitionTime);
        invalidateDownstream(stage, transitionTime);
        status = HarnessRunStatus.ACTIVE;
        touch(transitionTime);
        StageConversationMessage recorded = new StageConversationMessage(
                commandId, attempt, requested.getContent());
        addEvent("STAGE_CONVERSATION_MESSAGE", stage, actor, recorded.encode(), transitionTime);
        return StageConversationTurn.created(attempt, attemptOpened);
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

    /**
     * IMPLEMENTATION Attempt 打开后固定真实 Git 基线；其他阶段不生成 Artifact。
     */
    public HarnessGeneratedArtifact captureImplementationBaseline(HarnessStage stage,
                                                                  WorkspaceBaseline baseline,
                                                                  Instant now) {
        if (stage != HarnessStage.IMPLEMENTATION) {
            return null;
        }
        if (baseline == null || !workspaceBaseline.belongsToSameRepository(baseline)) {
            throw new IllegalHarnessTransitionException(
                    "implementation baseline does not match run repository");
        }
        ArtifactContent content = new ImplementationEvidenceFactory().baseline(baseline);
        ArtifactDescriptor descriptor = registerArtifact(HarnessStage.IMPLEMENTATION,
                "implementation-workspace-baseline", ArtifactType.CHANGED_FILES, content,
                "application/json", ArtifactClassification.INTERNAL, "harness-baseline",
                artifactSourceReferences(HarnessStage.IMPLEMENTATION), now);
        return new HarnessGeneratedArtifact(descriptor, content);
    }

    /**
     * 返回当前实现 Attempt 在 Runtime 写入前固化的 Git 基线 Artifact。
     */
    public ArtifactDescriptor implementationBaselineArtifact(RuntimeArtifactBundle bundle) {
        if (bundle == null || bundle.getStage() != HarnessStage.IMPLEMENTATION) {
            return null;
        }
        StageExecution implementation = stage(HarnessStage.IMPLEMENTATION);
        ArtifactDescriptor baseline = null;
        for (ArtifactDescriptor descriptor : artifacts) {
            if (descriptor.getStage() == HarnessStage.IMPLEMENTATION
                    && descriptor.getAttempt() == implementation.currentAttempt().getNumber()
                    && descriptor.getArtifactType() == ArtifactType.CHANGED_FILES
                    && "harness-baseline".equals(descriptor.getCreatedBy())
                    && (baseline == null || descriptor.getVersion() > baseline.getVersion())) {
                baseline = descriptor;
            }
        }
        if (baseline == null) {
            throw new IllegalHarnessTransitionException(
                    "implementation runtime result has no captured Git baseline");
        }
        return baseline;
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

    /**
     * 将阻断性问题收回当前 Attempt，并把 Run/Stage 同步置为等待补充输入。
     */
    public boolean requestInput(HarnessStage stage, String questionId, String question,
                                boolean blocking, String actor, Instant now) {
        requireMutable();
        StageExecution execution = stage(stage);
        String normalizedId = DomainText.require(questionId, "question id", 128);
        HarnessQuestion existing = question(normalizedId);
        if (existing != null) {
            if (!existing.matchesRequest(stage, execution.currentAttempt().getNumber(),
                    question, blocking)) {
                throw new IllegalHarnessTransitionException(
                        "question id already belongs to a different request");
            }
            return false;
        }
        if (execution.getStatus() != StageStatus.RUNNING) {
            throw new IllegalHarnessTransitionException(
                    "question can only be requested for a running stage: " + stage);
        }
        if (hasOpenBlockingQuestion(execution)) {
            throw new IllegalHarnessTransitionException(
                    "current attempt already has a blocking question");
        }
        Instant transitionTime = requireTime(now);
        questions.add(HarnessQuestion.ask(normalizedId, stage,
                execution.currentAttempt().getNumber(), question, blocking, actor, transitionTime));
        if (blocking) {
            execution.waitForInput();
            status = HarnessRunStatus.WAITING_INPUT;
        }
        touch(transitionTime);
        addEvent("INPUT_REQUESTED", stage, actor, normalizedId, transitionTime);
        return true;
    }

    /**
     * 回答当前 Attempt 的问题；最后一个阻断问题回答后恢复原 Attempt，不创建新 Attempt。
     */
    public boolean answerQuestion(String questionId, String answer, String actor, Instant now) {
        requireMutable();
        HarnessQuestion target = question(DomainText.require(questionId, "question id", 128));
        if (target == null) {
            throw new IllegalHarnessTransitionException("question does not exist: " + questionId);
        }
        StageExecution execution = stage(target.getStage());
        if (!target.belongsTo(target.getStage(), execution.currentAttempt().getNumber())) {
            throw new IllegalHarnessTransitionException("question does not belong to the current attempt");
        }
        Instant transitionTime = requireTime(now);
        if (!target.answer(answer, actor, transitionTime)) {
            return false;
        }
        if (target.isBlocking() && execution.getStatus() == StageStatus.WAITING_INPUT
                && !hasOpenBlockingQuestion(execution)) {
            execution.resumeAfterInput();
            status = HarnessRunStatus.ACTIVE;
        }
        touch(transitionTime);
        addEvent("INPUT_ANSWERED", target.getStage(), actor, target.getQuestionId(), transitionTime);
        return true;
    }

    /**
     * 返回目标阶段可消费的已批准上游 Artifact 当前版本。
     */
    public List<ArtifactDescriptor> approvedInputArtifacts(HarnessStage target) {
        StageExecution targetExecution = stage(target);
        List<ArtifactDescriptor> inputs = new ArrayList<ArtifactDescriptor>();
        for (ArtifactType required : targetExecution.getContract().getRequiredInputArtifacts()) {
            ArtifactDescriptor descriptor = approvedInputArtifact(target, required);
            if (descriptor == null) {
                throw new IllegalHarnessTransitionException(
                        "approved input artifact is missing for " + target + ": " + required);
            }
            inputs.add(descriptor);
        }
        inputs.sort(Comparator.comparing(item -> item.getArtifactType().name()));
        return Collections.unmodifiableList(inputs);
    }

    /**
     * 对已批准输入引用计算稳定 Hash，部署动作 Approval 只对该基线有效。
     */
    public String approvedInputBaselineHash(HarnessStage target) {
        StringBuilder canonical = new StringBuilder();
        for (ArtifactDescriptor descriptor : approvedInputArtifacts(target)) {
            canonical.append(descriptor.getArtifactType()).append(':')
                    .append(descriptor.getArtifactId()).append(':')
                    .append(descriptor.getVersion()).append(':')
                    .append(descriptor.getSha256()).append('\n');
        }
        return ArtifactContent.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 新输出必须引用其阶段合同输入；没有 ORIGINAL_REQUIREMENT 的 M1/M2 历史 Run 保持空引用兼容。
     */
    public List<ArtifactReference> artifactSourceReferences(HarnessStage target) {
        if (target == HarnessStage.ANALYSIS
                && artifactVersions(ArtifactType.ORIGINAL_REQUIREMENT).isEmpty()) {
            return Collections.emptyList();
        }
        List<ArtifactReference> references = new ArrayList<ArtifactReference>();
        for (ArtifactDescriptor descriptor : approvedInputArtifacts(target)) {
            references.add(descriptor.reference());
        }
        return Collections.unmodifiableList(references);
    }

    /**
     * 批准一次 local 部署动作；它独立于 DEPLOYMENT 阶段最终交付 Approval。
     */
    public boolean approveDeployment(String approvalId, String approvedInputBaselineHash,
                                     String actor, String reason, Instant now) {
        String normalizedId = DomainText.require(approvalId, "deployment approval id", 128);
        if (hasApproval(normalizedId)) {
            return false;
        }
        requireMutable();
        StageExecution deployment = stage(HarnessStage.DEPLOYMENT);
        if (deployment.getStatus() != StageStatus.RUNNING || !"local".equalsIgnoreCase(environment)) {
            throw new IllegalHarnessTransitionException(
                    "deployment action approval requires a running local deployment stage");
        }
        String requestedHash = DomainText.requireSha256(
                approvedInputBaselineHash, "deployment input baseline hash");
        if (!approvedInputBaselineHash(HarnessStage.DEPLOYMENT).equals(requestedHash)) {
            throw new IllegalHarnessTransitionException("deployment input baseline hash is stale");
        }
        Instant transitionTime = requireTime(now);
        approvals.add(Approval.approveAction(normalizedId, deployment, "LOCAL_DEPLOY",
                requestedHash, actor, reason, transitionTime));
        touch(transitionTime);
        addEvent("DEPLOYMENT_APPROVED", HarnessStage.DEPLOYMENT, actor,
                requestedHash, transitionTime);
        return true;
    }

    public boolean hasDeploymentApproval(String inputBaselineHash) {
        String normalizedHash = DomainText.requireSha256(
                inputBaselineHash, "deployment input baseline hash");
        for (Approval approval : approvals) {
            if (approval.isValid() && approval.getDecision() == ApprovalDecision.APPROVED
                    && approval.getStage() == HarnessStage.DEPLOYMENT
                    && approval.getAttempt() == stage(HarnessStage.DEPLOYMENT)
                    .currentAttempt().getNumber()
                    && "LOCAL_DEPLOY".equals(approval.getApprovalType())
                    && normalizedHash.equals(approval.getArtifactBaselineHash())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回当前 local DEPLOYMENT Attempt 的领域输入基线和独立 Approval 状态。
     */
    public DeploymentReadiness deploymentReadiness() {
        StageExecution deployment = stage(HarnessStage.DEPLOYMENT);
        if (deployment.getStatus() != StageStatus.RUNNING
                || !"local".equalsIgnoreCase(environment)) {
            throw new IllegalHarnessTransitionException(
                    "deployment readiness requires a running local deployment stage");
        }
        String inputBaselineHash = approvedInputBaselineHash(HarnessStage.DEPLOYMENT);
        return new DeploymentReadiness(id, environment,
                deployment.currentAttempt().getNumber(), inputBaselineHash,
                hasDeploymentApproval(inputBaselineHash));
    }

    /**
     * 校验 local、当前 Attempt、独立 Approval 与仓库身份后签发部署许可。
     */
    public DeploymentPermit authorizeDeployment(WorkspaceBaseline currentBaseline) {
        return authorizeDeployment(approvedInputBaselineHash(HarnessStage.DEPLOYMENT),
                currentBaseline);
    }

    public DeploymentPermit authorizeDeployment(String requestedInputBaselineHash,
                                                 WorkspaceBaseline currentBaseline) {
        requireMutable();
        StageExecution deployment = stage(HarnessStage.DEPLOYMENT);
        if (deployment.getStatus() != StageStatus.RUNNING
                || !"local".equalsIgnoreCase(environment)) {
            throw new IllegalHarnessTransitionException(
                    "deployment execution requires a running local deployment stage");
        }
        if (currentBaseline == null
                || !workspaceBaseline.belongsToSameRepository(currentBaseline)) {
            throw new IllegalHarnessTransitionException(
                    "deployment workspace does not match the run repository");
        }
        String inputHash = approvedInputBaselineHash(HarnessStage.DEPLOYMENT);
        if (!inputHash.equals(DomainText.requireSha256(
                requestedInputBaselineHash, "requested deployment input baseline hash"))) {
            throw new IllegalHarnessTransitionException(
                    "requested deployment input baseline hash is stale");
        }
        if (!hasDeploymentApproval(inputHash)) {
            throw new IllegalHarnessTransitionException(
                    "deployment execution requires a valid independent approval");
        }
        return new DeploymentPermit(id, deployment.currentAttempt().getNumber(),
                inputHash, currentBaseline);
    }

    public void recordDeploymentPrepared(String deploymentExecutionId, Instant now) {
        requireMutable();
        StageExecution deployment = stage(HarnessStage.DEPLOYMENT);
        if (deployment.getStatus() != StageStatus.RUNNING) {
            throw new IllegalHarnessTransitionException(
                    "deployment execution can only be prepared for a running stage");
        }
        Instant transitionTime = requireTime(now);
        touch(transitionTime);
        addEvent("DEPLOYMENT_EXECUTION_PREPARED", HarnessStage.DEPLOYMENT, createdBy,
                DomainText.require(deploymentExecutionId, "deployment execution id", 128),
                transitionTime);
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

    /**
     * 提供当前 Attempt 的最新输出版本给确定性 Gate；选择规则仍封装在聚合内。
     */
    public List<ArtifactDescriptor> gateArtifactDescriptors(HarnessStage stage) {
        StageExecution execution = stage(stage);
        if (execution.getStatus() != StageStatus.RUNNING) {
            throw new IllegalHarnessTransitionException(
                    "gate artifacts require a running stage: " + stage);
        }
        List<ArtifactDescriptor> descriptors = currentArtifacts(
                stage, execution.currentAttempt().getNumber());
        descriptors.sort(Comparator.comparing(item -> item.getArtifactType().name()));
        return Collections.unmodifiableList(descriptors);
    }

    /**
     * 返回最终交付报告可消费的三阶段已批准最新 Artifact。
     */
    public List<ArtifactDescriptor> deliveryReportArtifactDescriptors() {
        if (stage(HarnessStage.DEPLOYMENT).getStatus() != StageStatus.RUNNING) {
            throw new IllegalHarnessTransitionException(
                    "delivery report requires a running deployment stage");
        }
        List<ArtifactDescriptor> descriptors = new ArrayList<ArtifactDescriptor>();
        HarnessStage[] producers = {
                HarnessStage.ANALYSIS, HarnessStage.DESIGN, HarnessStage.IMPLEMENTATION
        };
        for (HarnessStage producerStage : producers) {
            StageExecution producer = stage(producerStage);
            if (producer.getStatus() != StageStatus.PASSED || !hasValidStageApproval(producer)) {
                throw new IllegalHarnessTransitionException(
                        "delivery report requires approved stage: " + producerStage);
            }
            descriptors.addAll(currentArtifacts(
                    producerStage, producer.currentAttempt().getNumber()));
        }
        descriptors.sort(Comparator
                .comparing((ArtifactDescriptor item) -> item.getStage().ordinal())
                .thenComparing(item -> item.getArtifactType().name()));
        return Collections.unmodifiableList(descriptors);
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
        requestCancellation(actor, reason, now);
        return true;
    }

    /**
     * 先记录取消业务意图；有活动 Runtime 时返回提交后需要执行的终止指令。
     *
     * @param actor 操作者
     * @param reason 原因
     * @param now 时间
     * @return 取消指令
     */
    public CancellationDirective requestCancellation(String actor, String reason, Instant now) {
        if (status == HarnessRunStatus.CANCELLED) {
            return CancellationDirective.completedWithoutRuntime();
        }
        if (status == HarnessRunStatus.CANCELLING) {
            return CancellationDirective.cancelRuntime(activeExecutionId());
        }
        requireMutable();
        String normalizedActor = DomainText.require(actor, "cancellation actor");
        String normalizedReason = DomainText.require(reason, "cancellation reason");
        Instant transitionTime = requireTime(now);
        StageExecution active = activeExecutionWithRuntime();
        if (active != null) {
            active.requestCancellation();
            invalidateApprovalsFrom(HarnessStage.ANALYSIS, transitionTime);
            status = HarnessRunStatus.CANCELLING;
            touch(transitionTime);
            String executionId = active.currentAttempt().getExecutionId();
            addEvent("RUN_CANCELLATION_REQUESTED", active.getStage(), normalizedActor,
                    normalizedReason + ":execution=" + executionId, transitionTime);
            return CancellationDirective.cancelRuntime(executionId);
        }
        for (StageExecution execution : stages) {
            execution.cancel(transitionTime);
        }
        invalidateApprovalsFrom(HarnessStage.ANALYSIS, transitionTime);
        status = HarnessRunStatus.CANCELLED;
        touch(transitionTime);
        addEvent("RUN_CANCELLED", null, normalizedActor, normalizedReason, transitionTime);
        return CancellationDirective.completedWithoutRuntime();
    }

    public void confirmCancellation(ExecutionReference reference, Instant now) {
        if (status != HarnessRunStatus.CANCELLING || !matchesActiveExecution(reference)) {
            throw new IllegalHarnessTransitionException(
                    "runtime cancellation does not match the active execution");
        }
        Instant transitionTime = requireTime(now);
        StageExecution active = stage(reference.getStage());
        active.confirmCancellation(transitionTime);
        for (StageExecution execution : stages) {
            if (execution != active) {
                execution.cancel(transitionTime);
            }
        }
        status = HarnessRunStatus.CANCELLED;
        touch(transitionTime);
        addEvent("RUN_CANCELLED", reference.getStage(), createdBy,
                "execution=" + reference.getExecutionId(), transitionTime);
    }

    public void recordExecutionFailure(ExecutionReference reference, String reason, Instant now) {
        requireMutable();
        if (!matchesActiveExecution(reference)) {
            throw new IllegalHarnessTransitionException(
                    "runtime failure does not match the active execution");
        }
        StageExecution execution = stage(reference.getStage());
        execution.failFromRuntime(reason, now);
        status = HarnessRunStatus.FAILED;
        touch(now);
        addEvent("RUNTIME_EXECUTION_FAILED", reference.getStage(), createdBy,
                reason, now);
    }

    public void recordExecutionSucceeded(ExecutionReference reference, Instant now) {
        requireMutable();
        if (!matchesActiveExecution(reference)) {
            throw new IllegalHarnessTransitionException(
                    "runtime success does not match the active execution");
        }
        touch(now);
        addEvent("RUNTIME_EXECUTION_SUCCEEDED", reference.getStage(), createdBy,
                reference.getExecutionId(), now);
    }

    /**
     * 将独立 RuntimeExecution 的终态映射为 Run 业务结果；技术成功不等于 Stage 通过。
     *
     * @param execution RuntimeExecution 聚合
     * @param now 映射时间
     * @return 是否改变了 Run
     */
    public boolean applyRuntimeExecutionOutcome(RuntimeExecutionOutcome outcome, Instant now) {
        if (outcome == null) {
            return false;
        }
        switch (outcome.getStatus()) {
            case SUCCEEDED:
                recordExecutionSucceeded(outcome.getReference(), now);
                return true;
            case CANCELLED:
                confirmCancellation(outcome.getReference(), now);
                return true;
            case FAILED:
            case TIMED_OUT:
            case LOST:
                recordExecutionFailure(outcome.getReference(), outcome.getTerminationReason(), now);
                return true;
            default:
                return false;
        }
    }

    /**
     * 将独立部署执行终态映射为 Run；成功仍需 Artifact Gate 和最终人工 Approval。
     */
    public boolean applyDeploymentExecutionOutcome(DeploymentExecutionOutcome outcome, Instant now) {
        if (outcome == null || !id.equals(outcome.getRunId())) {
            throw new IllegalArgumentException("deployment execution does not belong to run");
        }
        if (outcome.isSuccessful()) {
            touch(now);
            addEvent("DEPLOYMENT_EXECUTION_SUCCEEDED", HarnessStage.DEPLOYMENT, createdBy,
                    outcome.getExecutionId(), now);
            return true;
        }
        if (outcome.getStatus() == DeploymentExecutionStatus.FAILED) {
            StageExecution deployment = stage(HarnessStage.DEPLOYMENT);
            deployment.failFromRuntime(outcome.getFailureReason(), now);
            status = HarnessRunStatus.FAILED;
            touch(now);
            addEvent("DEPLOYMENT_EXECUTION_FAILED", HarnessStage.DEPLOYMENT, createdBy,
                    outcome.getFailureReason(), now);
            return true;
        }
        return false;
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
            if (execution.getStatus().occupiesActiveAttempt()) {
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

    private HarnessQuestion question(String questionId) {
        for (HarnessQuestion item : questions) {
            if (item.getQuestionId().equals(questionId)) {
                return item;
            }
        }
        return null;
    }

    private boolean hasOpenBlockingQuestion(StageExecution execution) {
        int attempt = execution.currentAttempt().getNumber();
        for (HarnessQuestion item : questions) {
            if (item.belongsTo(execution.getStage(), attempt)
                    && item.isBlocking() && !item.isAnswered()) {
                return true;
            }
        }
        return false;
    }

    private ArtifactDescriptor approvedInputArtifact(HarnessStage target, ArtifactType type) {
        ArtifactDescriptor latest = null;
        for (ArtifactDescriptor descriptor : artifacts) {
            if (descriptor.getArtifactType() != type) {
                continue;
            }
            if (target == HarnessStage.ANALYSIS && type == ArtifactType.ORIGINAL_REQUIREMENT) {
                if (latest == null || descriptor.getVersion() > latest.getVersion()) {
                    latest = descriptor;
                }
                continue;
            }
            if (descriptor.getStage().ordinal() >= target.ordinal()) {
                continue;
            }
            StageExecution producer = stage(descriptor.getStage());
            if (producer.getStatus() != StageStatus.PASSED
                    || producer.currentAttempt().getNumber() != descriptor.getAttempt()
                    || !hasValidStageApproval(producer)) {
                continue;
            }
            if (latest == null || descriptor.getVersion() > latest.getVersion()) {
                latest = descriptor;
            }
        }
        return latest;
    }

    private boolean hasValidStageApproval(StageExecution execution) {
        int attempt = execution.currentAttempt().getNumber();
        String baselineHash = currentArtifactBaselineHash(execution.getStage());
        for (Approval approval : approvals) {
            if (approval.isValid() && approval.getDecision() == ApprovalDecision.APPROVED
                    && approval.getStage() == execution.getStage()
                    && approval.getAttempt() == attempt
                    && execution.getContract().getApprovalType().equals(approval.getApprovalType())
                    && baselineHash.equals(approval.getArtifactBaselineHash())) {
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

    private boolean prepareConversationAttempt(StageExecution target,
                                               String commandId,
                                               Instant now) {
        switch (target.getStatus()) {
            case PENDING:
                requireNoWritableAttempt();
                requirePreviousPassed(target.getStage());
                target.startNewAttempt(commandId, now);
                addEvent("STAGE_STARTED", target.getStage(), createdBy,
                        "attempt=" + target.currentAttempt().getNumber(), now);
                return true;
            case RUNNING:
                if (target.currentAttempt().getExecutionId() == null) {
                    return false;
                }
                requireRuntimeSucceeded(target);
                target.supersedeAndStartNewAttempt(commandId, now);
                addConversationRevisionEvent(target, now);
                return true;
            case WAITING_APPROVAL:
                target.supersedeAndStartNewAttempt(commandId, now);
                addConversationRevisionEvent(target, now);
                return true;
            case PASSED:
            case INVALIDATED:
                requireNoWritableAttempt();
                requirePreviousPassed(target.getStage());
                target.supersedeAndStartNewAttempt(commandId, now);
                addConversationRevisionEvent(target, now);
                return true;
            case FAILED:
                requireNoWritableAttempt();
                requirePreviousPassed(target.getStage());
                target.startNewAttempt(commandId, now);
                addConversationRevisionEvent(target, now);
                return true;
            case WAITING_INPUT:
                throw new IllegalHarnessTransitionException(
                        "answer the blocking question before revising the stage");
            default:
                throw new IllegalHarnessTransitionException(
                        "stage cannot accept conversation from " + target.getStatus());
        }
    }

    private void requireRuntimeSucceeded(StageExecution execution) {
        String executionId = execution.currentAttempt().getExecutionId();
        for (HarnessEvent event : events) {
            if ("RUNTIME_EXECUTION_SUCCEEDED".equals(event.getEventType())
                    && event.getStage() == execution.getStage()
                    && executionId.equals(event.getDetail())) {
                return;
            }
        }
        throw new IllegalHarnessTransitionException(
                "current runtime is still executing; cancel it before revising the stage");
    }

    private void addConversationRevisionEvent(StageExecution target, Instant now) {
        addEvent("STAGE_CONVERSATION_REVISION_STARTED", target.getStage(), createdBy,
                "attempt=" + target.currentAttempt().getNumber(), now);
    }

    private StageConversationMessage conversationMessage(HarnessStage stage, String commandId) {
        for (HarnessEvent event : events) {
            if ("STAGE_CONVERSATION_MESSAGE".equals(event.getEventType())
                    && event.getStage() == stage) {
                StageConversationMessage message = StageConversationMessage.decode(event.getDetail());
                if (message.getCommandId().equals(commandId)) {
                    return message;
                }
            }
        }
        return null;
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
        if (status.isTerminal() || status == HarnessRunStatus.CANCELLING) {
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
            if (execution.getStatus().occupiesActiveAttempt()) {
                writable++;
            }
        }
        if (writable > 1) {
            throw new IllegalArgumentException("run must have at most one writable attempt");
        }
        if (status == HarnessRunStatus.COMPLETED && !allStagesPassed()) {
            throw new IllegalArgumentException("completed run requires all stages passed");
        }
        if (status == HarnessRunStatus.CANCELLING && writable != 1) {
            throw new IllegalArgumentException("cancelling run requires one active attempt");
        }
        Set<String> questionIds = new java.util.HashSet<String>();
        for (HarnessQuestion item : questions) {
            if (!questionIds.add(item.getQuestionId())) {
                throw new IllegalArgumentException("question ids must be unique");
            }
        }
    }

    private StageExecution activeExecutionWithRuntime() {
        for (StageExecution execution : stages) {
            if (execution.getStatus().isWritable()
                    && execution.currentAttempt().getExecutionId() != null) {
                return execution;
            }
        }
        return null;
    }

    private String activeExecutionId() {
        for (StageExecution execution : stages) {
            if (execution.getStatus() == StageStatus.CANCELLING) {
                return execution.currentAttempt().getExecutionId();
            }
        }
        throw new IllegalStateException("cancelling run has no active execution");
    }

    private boolean matchesActiveExecution(ExecutionReference reference) {
        if (reference == null || !id.equals(reference.getRunId())) {
            return false;
        }
        StageExecution execution = stage(reference.getStage());
        StageAttempt attempt = execution.currentAttempt();
        return attempt.getNumber() == reference.getAttemptNumber()
                && reference.getExecutionId().equals(attempt.getExecutionId())
                && reference.getSnapshotHash().equals(attempt.getSnapshotHash());
    }

    private static <T> List<T> copy(List<T> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return new ArrayList<T>(values);
    }
}
