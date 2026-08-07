package com.example.agentweb.app.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.AgentRuntimeSurface;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeSelectionStore;
import com.example.agentweb.app.runtime.port.ExecutionIdentity;
import com.example.agentweb.app.runtime.port.HistoryDelivery;
import com.example.agentweb.app.runtime.port.PromptPayload;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimeAttachmentExpectation;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeProfileSelector;
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
import com.example.agentweb.domain.workbench.WorkbenchStageRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshotRepository;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageUploadedConversationAttachment;
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

    private final WorkbenchStageRunSnapshotRepository snapshotRepository;
    private final WorkbenchStageRunPromptPayloadRepository promptRepository;
    private final WorkbenchRepository workbenchRepository;
    private final RuntimeProfileSelector profileSelector;
    private final ChatRunRuntimeSelectionStore selectionStore;

    public WorkbenchExecutionPlanProvider(
            WorkbenchStageRunSnapshotRepository snapshotRepository,
            WorkbenchStageRunPromptPayloadRepository promptRepository,
            WorkbenchRepository workbenchRepository) {
        this(snapshotRepository, promptRepository, workbenchRepository, null);
    }

    public WorkbenchExecutionPlanProvider(
            WorkbenchStageRunSnapshotRepository snapshotRepository,
            WorkbenchStageRunPromptPayloadRepository promptRepository,
            WorkbenchRepository workbenchRepository,
            RuntimeProfileSelector profileSelector) {
        this(snapshotRepository, promptRepository, workbenchRepository,
                profileSelector, null);
    }

    public WorkbenchExecutionPlanProvider(
            WorkbenchStageRunSnapshotRepository snapshotRepository,
            WorkbenchStageRunPromptPayloadRepository promptRepository,
            WorkbenchRepository workbenchRepository,
            RuntimeProfileSelector profileSelector,
            ChatRunRuntimeSelectionStore selectionStore) {
        this.snapshotRepository = Objects.requireNonNull(
                snapshotRepository, "snapshotRepository");
        this.promptRepository = Objects.requireNonNull(
                promptRepository, "promptRepository");
        this.workbenchRepository = Objects.requireNonNull(
                workbenchRepository, "workbenchRepository");
        this.profileSelector = profileSelector;
        this.selectionStore = selectionStore;
    }

    @Override
    public boolean supports(RunOrigin origin) {
        return origin == RunOrigin.WORKBENCH;
    }

    @Override
    public AgentExecutionPlan prepare(ChatRun run) {
        ChatRun requiredRun = Objects.requireNonNull(run, "run");
        String runId = requiredRun.getId().getValue();
        WorkbenchStageRunSnapshot snapshot = snapshotRepository
                .findByRunId(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "persisted Workbench Runtime snapshot is unavailable"));
        WorkbenchRunPromptPayload prompt = promptRepository
                .findByRunId(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "persisted Workbench Runtime prompt is unavailable"));
        snapshot.requirePromptPayload(prompt);
        Workbench workbench = workbenchRepository.findById(snapshot.getWorkbenchId())
                .orElseThrow(() -> new IllegalStateException(
                        "persisted Workbench is unavailable"));
        snapshot.requireExactRun(workbench, requiredRun, runId);

        RuntimeEnforcementSnapshot runtime = snapshot.getRuntimeEnforcement();
        AgentType agentType = AgentType.parseKnown(runtime.getRuntime());
        boolean profilesConfigured = profileSelector != null
                && profileSelector.hasProfiles();
        if (!profilesConfigured && agentType != AgentType.CODEX) {
            throw new IllegalStateException(
                    "common Workbench Runtime currently supports Codex only");
        }
        SandboxMode sandboxMode = SANDBOX_MODES.get(runtime.getRunMode());
        if (sandboxMode == null) {
            throw new IllegalStateException("unsupported Workbench Runtime mode");
        }
        RepositoryScope scope = workbench.getRepositoryScope();
        runtime.requireRepositoryScope(scope);

        String primaryRoot = scope.primaryRepository().getRepositoryRoot();
        List<String> readableRoots = scope.repositoryRoots();
        List<String> writableRoots = scope.requireRepositoryRoots(
                runtime.getWritableRepositoryKeys());
        String attachmentPrimaryRoot = primaryRoot;
        if (workbench.isUseWorktree()) {
            String worktreeRoot = workbench.getWorktreePath();
            String primaryKey = scope.getPrimaryRepositoryKey();
            primaryRoot = worktreeRoot;
            readableRoots = substituteRootByKey(
                    readableRoots, scope, primaryKey, worktreeRoot);
            writableRoots = substituteRootByKey(
                    writableRoots, scope, primaryKey, worktreeRoot);
            attachmentPrimaryRoot = worktreeRoot;
        }

        RuntimeSelection persistedSelection = selectionStore == null ? null
                : selectionStore.find(requiredRun.getId()).orElse(null);
        RuntimeSelection runtimeSelection;
        if (persistedSelection != null) {
            runtimeSelection = persistedSelection;
        } else if (!profilesConfigured) {
            runtimeSelection = new RuntimeSelection(agentType,
                    RuntimeVersionPolicy.exact(runtime.getRuntimeVersion()));
        } else {
            runtimeSelection = profileSelection(agentType, runtime);
        }
        return new AgentExecutionPlan(
                new ExecutionIdentity(
                        runId, workbench.getOwner().getOwnerId(),
                        snapshot.executionOriginReference()),
                runtimeSelection,
                new PromptPayload(
                        prompt.getFinalPrompt(), prompt.getPromptHash(),
                        HistoryDelivery.valueOf(
                                prompt.getHistoryDelivery().name())),
                new WorkspaceLayout(
                        scope.getWorkspaceRoot(),
                        primaryRoot,
                        readableRoots,
                        writableRoots,
                        sandboxMode),
                snapshot.getCapabilityBinding(),
                new RuntimeLimits(
                        Duration.ofSeconds(runtime.getTimeoutSeconds()),
                        runtime.getOutputLimitBytes()),
                attachmentExpectations(snapshot, scope, attachmentPrimaryRoot));
    }

    private RuntimeSelection profileSelection(AgentType agentType,
                                               RuntimeEnforcementSnapshot runtime) {
        RuntimeSelection selected = profileSelector.selection(agentType,
                AgentRuntimeSurface.WORKBENCH, runtime.getRunMode(), null, null, null);
        return new RuntimeSelection(selected.getProfileId(), selected.getAgentType(),
                selected.getEndpoint(), selected.getModel(), selected.getReasoningEffort(),
                selected.getRuntimeEnvironment(),
                RuntimeVersionPolicy.exact(runtime.getRuntimeVersion()));
    }

    private List<RuntimeAttachmentExpectation> attachmentExpectations(
            WorkbenchStageRunSnapshot snapshot, RepositoryScope scope,
            String primaryRoot) {
        List<RuntimeAttachmentExpectation> result =
                new ArrayList<RuntimeAttachmentExpectation>();
        appendRepositoryAttachments(
                result, snapshot.getVerifiedAttachments(), scope, primaryRoot);
        for (VerifiedWorkbenchStageUploadedConversationAttachment attachment
                : snapshot.getVerifiedUploadedAttachments()) {
            result.add(RuntimeAttachmentExpectation.uploadedConversation(
                    attachment.getAttachmentId(), attachment.getStorageKey(),
                    attachment.getRuntimeFileName(),
                    attachment.getContentHash(), attachment.getSize()));
        }
        return result;
    }

    private void appendRepositoryAttachments(
            List<RuntimeAttachmentExpectation> result,
            List<VerifiedWorkbenchRunAttachment> attachments,
            RepositoryScope scope, String primaryRoot) {
        for (VerifiedWorkbenchRunAttachment attachment : attachments) {
            ResolvedRepository repository = scope.requireRepository(
                    attachment.getDocumentReference().getRepositoryKey());
            String root = scope.getPrimaryRepositoryKey().equals(
                    repository.getRepositoryKey())
                    ? primaryRoot : repository.getRepositoryRoot();
            result.add(new RuntimeAttachmentExpectation(
                    repository.getRepositoryKey(),
                    root,
                    attachment.getDocumentReference().getRelativePath(),
                    attachment.getContentVersion(), attachment.getSize()));
        }
    }

    /**
     * 按 key 替换列表中 primary 仓库的根路径为 worktree 路径，其余仓库保持不变。
     * 不得按下标替换——RepositoryScope 按 repositoryKey 字典序排序，primary 不保证在首位。
     */
    private static List<String> substituteRootByKey(
            List<String> roots, RepositoryScope scope,
            String primaryKey, String worktreeRoot) {
        List<String> result = new ArrayList<String>(roots.size());
        for (String root : roots) {
            ResolvedRepository repo = findRepositoryByRoot(scope, root);
            if (repo != null && primaryKey.equals(repo.getRepositoryKey())) {
                result.add(worktreeRoot);
            } else {
                result.add(root);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static ResolvedRepository findRepositoryByRoot(
            RepositoryScope scope, String root) {
        for (ResolvedRepository repo : scope.getRepositories()) {
            if (repo.getRepositoryRoot().equals(root)) {
                return repo;
            }
        }
        return null;
    }

    private static Map<RunMode, SandboxMode> sandboxModes() {
        EnumMap<RunMode, SandboxMode> modes =
                new EnumMap<RunMode, SandboxMode>(RunMode.class);
        modes.put(RunMode.DISCUSS_READ_ONLY, SandboxMode.READ_ONLY);
        modes.put(RunMode.MODIFY_WORKSPACE, SandboxMode.WORKSPACE_WRITE);
        return Collections.unmodifiableMap(modes);
    }
}
