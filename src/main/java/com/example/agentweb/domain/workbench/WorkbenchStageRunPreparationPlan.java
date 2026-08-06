package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationProvisioning;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Workbench 聚合冻结的动态 Stage Run 准备要求。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageRunPreparationPlan {

    public enum WorkspaceAccess {
        READ_ONLY,
        WORKSPACE_WRITE
    }

    private final WorkbenchId workbenchId;
    private final String stageInstanceIdentifier;
    private final WorkbenchStageSnapshot stageSnapshot;
    private final RunMode runMode;
    private final AgentType agentType;
    private final String environment;
    private final String title;
    private final String originalGoal;
    private final RepositoryScope repositoryScope;
    private final WorkbenchStageConversationProvisioning conversation;
    private final List<String> readableRepositoryRoots;
    private final List<String> writableRepositoryRoots;
    private final List<String> writableRepositoryKeys;
    private final WorkspaceAccess workspaceAccess;
    private final List<WorkbenchStageHandoff> stageHandoffs;

    private WorkbenchStageRunPreparationPlan(
            Workbench workbench, WorkbenchStageState stage,
            RunMode runMode,
            WorkbenchStageConversationProvisioning conversation) {
        if (workbench == null || stage == null || runMode == null
                || conversation == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage Run preparation facts are required");
        }
        stage.requireRunPreparationAvailable(runMode);
        if (workbench.getAgentType() == AgentType.NATIVE) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "NATIVE diagnosis Runtime is unavailable to Workbench Stage");
        }
        this.workbenchId = workbench.getId();
        this.stageInstanceIdentifier = stage.getStageInstanceIdentifier();
        this.stageSnapshot = stage.getSnapshot();
        this.runMode = runMode;
        this.agentType = workbench.getAgentType();
        this.environment = workbench.getEnvironment();
        this.title = workbench.getTitle();
        this.originalGoal = workbench.getOriginalGoal();
        this.repositoryScope = workbench.getRepositoryScope();
        this.conversation = conversation;
        this.readableRepositoryRoots = repositoryRoots(repositoryScope);
        if (runMode.modifiesWorkspace()) {
            this.writableRepositoryRoots = readableRepositoryRoots;
            this.writableRepositoryKeys = repositoryKeys(repositoryScope);
            this.workspaceAccess = WorkspaceAccess.WORKSPACE_WRITE;
        } else {
            this.writableRepositoryRoots = Collections.emptyList();
            this.writableRepositoryKeys = Collections.emptyList();
            this.workspaceAccess = WorkspaceAccess.READ_ONLY;
        }
        this.stageHandoffs = stageHandoffs(workbench);
    }

    static WorkbenchStageRunPreparationPlan plan(
            Workbench workbench, WorkbenchStageState stage,
            RunMode runMode,
            WorkbenchStageConversationProvisioning conversation) {
        return new WorkbenchStageRunPreparationPlan(
                workbench, stage, runMode, conversation);
    }

    public void requireDevelopmentContext(
            WorkspaceDevelopmentContext developmentContext) {
        if (developmentContext == null) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        developmentContext.requireScope(repositoryScope);
    }

    public WorkbenchStageUploadedAttachmentBinding
            uploadedAttachmentBinding() {
        return new WorkbenchStageUploadedAttachmentBinding(
                conversation.getOwner(), workbenchId,
                stageInstanceIdentifier,
                conversation.requireCurrentConversationId(),
                conversation.getCurrentConversationGeneration());
    }

    private static List<String> repositoryRoots(RepositoryScope scope) {
        List<String> roots = new ArrayList<String>();
        for (ResolvedRepository repository : scope.getRepositories()) {
            roots.add(repository.getRepositoryRoot());
        }
        return Collections.unmodifiableList(roots);
    }

    private static List<String> repositoryKeys(RepositoryScope scope) {
        List<String> keys = new ArrayList<String>();
        for (ResolvedRepository repository : scope.getRepositories()) {
            keys.add(repository.getRepositoryKey());
        }
        return Collections.unmodifiableList(keys);
    }

    private static List<WorkbenchStageHandoff> stageHandoffs(Workbench workbench) {
        List<WorkbenchStageHandoff> entries =
                new ArrayList<WorkbenchStageHandoff>();
        for (WorkbenchStageState state : workbench.getStages()) {
            WorkbenchStageSnapshot snapshot = state.getSnapshot();
            entries.add(new WorkbenchStageHandoff(
                    snapshot.getDefinitionIdentifier(),
                    snapshot.getSequenceNumber(),
                    snapshot.getDisplayName()));
        }
        return Collections.unmodifiableList(entries);
    }
}
