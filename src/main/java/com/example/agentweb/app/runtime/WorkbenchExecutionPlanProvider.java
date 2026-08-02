package com.example.agentweb.app.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.ExecutionIdentity;
import com.example.agentweb.app.runtime.port.HistoryDelivery;
import com.example.agentweb.app.runtime.port.PromptPayload;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimeAttachmentExpectation;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.VerifiedUploadedConversationAttachment;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.ResolvedRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从 Workbench 已冻结 Snapshot、私有 Prompt 与不可变 Repository Scope 组装执行计划。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchExecutionPlanProvider
        implements ExecutionPlanProvider {

    private static final Map<RunMode, SandboxMode> SANDBOX_MODES =
            sandboxModes();

    private final WorkbenchRunSnapshotRepository snapshotRepository;
    private final WorkbenchRunPromptPayloadRepository promptRepository;
    private final WorkbenchRepository workbenchRepository;

    public WorkbenchExecutionPlanProvider(
            WorkbenchRunSnapshotRepository snapshotRepository,
            WorkbenchRunPromptPayloadRepository promptRepository,
            WorkbenchRepository workbenchRepository) {
        this.snapshotRepository = Objects.requireNonNull(
                snapshotRepository, "snapshotRepository");
        this.promptRepository = Objects.requireNonNull(
                promptRepository, "promptRepository");
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
    }

    @Override
    public boolean supports(RunOrigin origin) {
        return origin == RunOrigin.WORKBENCH;
    }

    @Override
    public AgentExecutionPlan prepare(ChatRun run) {
        ChatRun requiredRun = Objects.requireNonNull(run, "run");
        String runId = requiredRun.getId().getValue();
        WorkbenchRunSnapshot snapshot = snapshotRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "persisted Workbench Runtime snapshot is unavailable"));
        WorkbenchRunPromptPayload prompt = promptRepository.findByRunId(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "persisted Workbench Runtime prompt is unavailable"));
        snapshot.requirePromptPayload(prompt);
        Workbench workbench = workbenchRepository.findById(snapshot.getWorkbenchId())
                .orElseThrow(() -> new IllegalStateException(
                        "persisted Workbench is unavailable"));
        snapshot.requireExactRun(workbench, requiredRun, runId);

        RuntimeEnforcementSnapshot runtime = snapshot.getRuntimeEnforcement();
        AgentType agentType = AgentType.parseKnown(runtime.getRuntime());
        if (agentType != AgentType.CODEX) {
            throw new IllegalStateException(
                    "common Workbench Runtime currently supports Codex only");
        }
        SandboxMode sandboxMode = SANDBOX_MODES.get(runtime.getRunMode());
        if (sandboxMode == null) {
            throw new IllegalStateException("unsupported Workbench Runtime mode");
        }
        RepositoryScope scope = workbench.getRepositoryScope();
        runtime.requireRepositoryScope(scope);

        return new AgentExecutionPlan(
                new ExecutionIdentity(
                        runId, workbench.getOwner().getOwnerId(),
                        snapshot.executionOriginReference()),
                new RuntimeSelection(
                        agentType,
                        RuntimeVersionPolicy.exact(runtime.getRuntimeVersion())),
                new PromptPayload(
                        prompt.getFinalPrompt(), prompt.getPromptHash(),
                        HistoryDelivery.valueOf(
                                prompt.getHistoryDelivery().name())),
                new WorkspaceLayout(
                        scope.getWorkspaceRoot(),
                        scope.primaryRepository().getRepositoryRoot(),
                        scope.repositoryRoots(),
                        scope.requireRepositoryRoots(
                                runtime.getWritableRepositoryKeys()),
                        sandboxMode),
                snapshot.getCapabilityBinding(),
                new RuntimeLimits(
                        Duration.ofSeconds(runtime.getTimeoutSeconds()),
                        runtime.getOutputLimitBytes()),
                attachmentExpectations(snapshot, scope));
    }

    private List<RuntimeAttachmentExpectation> attachmentExpectations(
            WorkbenchRunSnapshot snapshot, RepositoryScope scope) {
        List<RuntimeAttachmentExpectation> result =
                new ArrayList<RuntimeAttachmentExpectation>();
        for (VerifiedWorkbenchRunAttachment attachment
                : snapshot.getVerifiedAttachments()) {
            ResolvedRepository repository = scope.requireRepository(
                    attachment.getDocumentReference().getRepositoryKey());
            result.add(new RuntimeAttachmentExpectation(
                    repository.getRepositoryKey(),
                    repository.getRepositoryRoot(),
                    attachment.getDocumentReference().getRelativePath(),
                    attachment.getContentVersion(), attachment.getSize()));
        }
        for (VerifiedUploadedConversationAttachment attachment
                : snapshot.getVerifiedUploadedAttachments()) {
            result.add(RuntimeAttachmentExpectation.uploadedConversation(
                    attachment.getAttachmentId(), attachment.getStorageKey(),
                    attachment.getRuntimeFileName(),
                    attachment.getContentHash(), attachment.getSize()));
        }
        return result;
    }

    private static Map<RunMode, SandboxMode> sandboxModes() {
        EnumMap<RunMode, SandboxMode> modes =
                new EnumMap<RunMode, SandboxMode>(RunMode.class);
        modes.put(RunMode.DISCUSS_READ_ONLY, SandboxMode.READ_ONLY);
        modes.put(RunMode.MODIFY_WORKSPACE, SandboxMode.WORKSPACE_WRITE);
        return Collections.unmodifiableMap(modes);
    }
}
