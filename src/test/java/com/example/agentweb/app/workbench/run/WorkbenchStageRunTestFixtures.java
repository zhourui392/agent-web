package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageRunAttachmentSet;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchRunAttachmentReference;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryBaseline;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceTopology;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * Dynamic Stage Run 应用测试使用的可信领域事实。
 *
 * @author alex
 * @since 2026-08-05
 */
final class WorkbenchStageRunTestFixtures {

    static final Instant NOW = Instant.parse("2026-08-05T17:00:00Z");
    static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");
    static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-stage-submit");
    static final String STAGE_INSTANCE_IDENTIFIER = "stage-design";
    static final String SESSION_IDENTIFIER = "stage-session-1";
    static final String RUN_IDENTIFIER = "stage-run-1";

    private WorkbenchStageRunTestFixtures() {
    }

    static Fixture withoutUpload() {
        return fixture(false, RunMode.DISCUSS_READ_ONLY, null);
    }

    static Fixture withUpload() {
        return fixture(true, RunMode.DISCUSS_READ_ONLY, null);
    }

    static Fixture withModifyUpload() {
        return fixture(true, RunMode.MODIFY_WORKSPACE, null);
    }

    private static Fixture fixture(
            boolean includeUpload, RunMode runMode,
            ResolvedCapabilityBinding requestedCapabilityBinding) {
        RepositoryScope scope = repositoryScope();
        WorkbenchStageSnapshot stageSnapshot = stageSnapshot();
        WorkspaceSnapshot workspaceSnapshot = workspaceSnapshot(scope);
        Workbench workbench = Workbench.create(
                WORKBENCH_ID, OWNER, "Dynamic Workbench",
                "Complete the selected Stage.", AgentType.CODEX, "local",
                scope, workspaceSnapshot.reference(),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_INSTANCE_IDENTIFIER, stageSnapshot)), NOW);
        workbench.bindStageConversation(
                STAGE_INSTANCE_IDENTIFIER, SESSION_IDENTIFIER,
                OWNER, 0L, NOW.plusSeconds(1));
        ChatSession session = ChatSession.createWorkbenchStage(
                SESSION_IDENTIFIER, AgentType.CODEX,
                scope.primaryRepository().getRepositoryRoot(),
                contextIdentifier(), OWNER.getOwnerId(), OWNER.getOwnerName(),
                NOW.plusSeconds(1));
        session.setEnv("local");

        WorkbenchStageUploadedConversationAttachment uploadedAttachment =
                includeUpload ? uploadedAttachment() : null;
        VerifiedWorkbenchStageRunAttachmentSet attachmentSet = includeUpload
                ? VerifiedWorkbenchStageRunAttachmentSet.of(
                        Collections.emptyList(), Collections.singletonList(
                                uploadedAttachment.verifyForRun(
                                        uploadedAttachment.binding(),
                                        repeat('8'), NOW.plusSeconds(2))))
                : VerifiedWorkbenchStageRunAttachmentSet.empty();
        SubmitWorkbenchStageRunCommand command =
                new SubmitWorkbenchStageRunCommand(
                        WORKBENCH_ID, STAGE_INSTANCE_IDENTIFIER,
                        workbench.getVersion(),
                        includeUpload
                                ? "stage-submit-upload" : "stage-submit-1",
                        "Finish the design.", runMode,
                        includeUpload
                                ? Collections.singletonList(
                                        WorkbenchRunAttachmentReference
                                                .uploadedConversation(
                                                        "stage-attachment-1",
                                                        repeat('8')))
                                : Collections.emptyList());
        WorkbenchRunPromptPayload promptPayload =
                WorkbenchRunPromptPayload.freeze(
                        RUN_IDENTIFIER, "Dynamic Stage final prompt",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX,
                        NOW.plusSeconds(2));
        ResolvedCapabilityBinding capabilityBinding =
                requestedCapabilityBinding == null
                ? ResolvedCapabilityBinding.resolve(
                        "stage-policy@1", "solution-design", "1",
                        stageSnapshot.getSnapshotHash(),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(),
                        "m0-2026-07-22")
                : requestedCapabilityBinding;
        WorkbenchStageRunSnapshot snapshot = WorkbenchStageRunSnapshot.create(
                RUN_IDENTIFIER, WORKBENCH_ID, STAGE_INSTANCE_IDENTIFIER,
                stageSnapshot, command.getIdempotencyKey(),
                command.getRequestHash(), command.getRunMode(), scope,
                workspaceSnapshot.reference(), capabilityBinding, null,
                0L, repeat('4'), Collections.emptyList(),
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner/current-message",
                        repeat('5'), 18)),
                promptPayload.getPromptHash(),
                runtimeEnforcement(runMode, scope),
                attachmentSet.getRepositoryDocuments(),
                attachmentSet.getUploadedAttachments(),
                NOW.plusSeconds(2));
        PreparedWorkbenchStageRun prepared = PreparedWorkbenchStageRun.of(
                command, snapshot, workspaceSnapshot,
                promptPayload, attachmentSet);
        return new Fixture(
                workbench, session, command, snapshot, workspaceSnapshot,
                promptPayload, prepared, uploadedAttachment);
    }

    static String contextIdentifier() {
        return WORKBENCH_ID.getValue() + ":" + STAGE_INSTANCE_IDENTIFIER;
    }

    static Fixture withCapabilityBinding(
            ResolvedCapabilityBinding capabilityBinding) {
        return fixture(
                false, RunMode.DISCUSS_READ_ONLY, capabilityBinding);
    }

    static ChatRun pendingRun() {
        return ChatRun.submit(
                ChatRunId.of(RUN_IDENTIFIER), SESSION_IDENTIFIER, 1L,
                "stage-submit-1", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        contextIdentifier(), RUN_IDENTIFIER),
                NOW.plusSeconds(2));
    }

    static ChatRun runningRun() {
        ChatRun run = pendingRun();
        run.start(NOW.plusSeconds(3));
        return run;
    }

    static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, Character.toString(value)));
    }

    private static WorkbenchStageUploadedConversationAttachment
            uploadedAttachment() {
        return WorkbenchStageUploadedConversationAttachment.upload(
                "stage-attachment-1",
                new WorkbenchStageUploadedAttachmentBinding(
                        OWNER, WORKBENCH_ID, STAGE_INSTANCE_IDENTIFIER,
                        SESSION_IDENTIFIER, 0),
                "design.md", "text/markdown",
                UploadedAttachmentContentSignature.TEXT,
                64L, repeat('8'), repeat('9'),
                UploadedAttachmentPolicy.standard(
                        1024L, 8, Duration.ofHours(24),
                        Duration.ofHours(2)),
                NOW.plusSeconds(1));
    }

    private static WorkbenchStageSnapshot stageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(
                "solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "形成完整方案", "保持领域边界清晰",
                        Set.of(RunMode.DISCUSS_READ_ONLY,
                                RunMode.MODIFY_WORKSPACE),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(2));
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        "solution-design", catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList()),
                        administrator, NOW.minusSeconds(1)));
    }

    private static RepositoryScope repositoryScope() {
        return RepositoryScope.create(
                "/workspace",
                RepositorySelection.of(
                        "agent-web", Collections.singletonList("agent-web")),
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('1'), false)),
                8);
    }

    private static WorkspaceSnapshot workspaceSnapshot(
            RepositoryScope scope) {
        WorkspaceTopology topology = WorkspaceTopology.of(
                "/workspace", RepositorySelection.of(
                        "agent-web", Collections.singletonList("agent-web")));
        return WorkspaceSnapshot.capture(
                "stage-run-workspace-snapshot",
                SnapshotPurpose.of("WORKBENCH_RUN_START"), topology,
                Collections.singletonList(RepositoryBaseline.capture(
                        "agent-web", "/workspace/agent-web", "master",
                        String.join("", Collections.nCopies(40, "1")),
                        true, repeat('2'), NOW)),
                Collections.emptyList(), NOW, NOW.plusMillis(1));
    }

    private static RuntimeEnforcementSnapshot runtimeEnforcement(
            RunMode runMode, RepositoryScope scope) {
        if (runMode.modifiesWorkspace()) {
            return RuntimeEnforcementSnapshot.modify(
                    "CODEX", "0.42.0", scope.getScopeHash(),
                    scope.getPrimaryRepositoryKey(),
                    Collections.singletonList(
                            scope.getPrimaryRepositoryKey()),
                    1800L, 8_388_608L);
        }
        return RuntimeEnforcementSnapshot.readOnly(
                "CODEX", "0.42.0", scope.getScopeHash(),
                scope.getPrimaryRepositoryKey(),
                1800L, 8_388_608L);
    }

    record Fixture(
            Workbench workbench,
            ChatSession session,
            SubmitWorkbenchStageRunCommand command,
            WorkbenchStageRunSnapshot snapshot,
            WorkspaceSnapshot workspaceSnapshot,
            WorkbenchRunPromptPayload promptPayload,
            PreparedWorkbenchStageRun prepared,
            WorkbenchStageUploadedConversationAttachment
                    uploadedAttachment) {
    }
}
