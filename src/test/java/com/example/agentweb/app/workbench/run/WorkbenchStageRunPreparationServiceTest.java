package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunIdGenerator;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimePreflightGateway;
import com.example.agentweb.app.runtime.port.RuntimePreflightReport;
import com.example.agentweb.app.runtime.port.RuntimePreflightRequest;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.workbench.WorkspaceSnapshotIdGenerator;
import com.example.agentweb.app.workbench.document.port.ScopedDocumentGateway;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.app.workbench.port.WorkspaceDevelopmentContextGateway;
import com.example.agentweb.app.workbench.port.WorkspaceSnapshotGateway;
import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RepositoryDevelopmentContextClassifier;
import com.example.agentweb.domain.workbench.RepositoryDevelopmentMarker;
import com.example.agentweb.domain.workbench.ResolvedCapabilityResolution;
import com.example.agentweb.domain.workbench.ResolvedCapabilityRuleContent;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchPromptPartType;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunAttachmentReference;
import com.example.agentweb.domain.workbench.WorkbenchStageConversationHistory;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.WorkspaceDevelopmentContext;
import com.example.agentweb.domain.workbench.context.WorkbenchContextManifest;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCapabilityResolver;
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
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dynamic Stage Run 的准备编排测试。
 *
 * @author alex
 * @since 2026-08-05
 */
@ExtendWith(MockitoExtension.class)
class WorkbenchStageRunPreparationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T14:00:00Z");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-stage-prepare-1");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final String STAGE_INSTANCE_ID = "stage-design";
    private static final String SESSION_ID = "stage-session-1";
    private static final String RUN_ID = "stage-run-1";
    private static final String RUNTIME_COMPATIBILITY = "m0-2026-07-22";

    @Mock private WorkbenchRunAvailability availability;
    @Mock private WorkbenchRepository workbenchRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private WorkbenchStageHistoryQuery historyQuery;
    @Mock private WorkbenchContextManifestQuery contextManifestQuery;
    @Mock private WorkbenchStageCapabilityResolver capabilityResolver;
    @Mock private WorkspaceSnapshotIdGenerator snapshotIdGenerator;
    @Mock private WorkspaceSnapshotGateway snapshotGateway;
    @Mock private WorkspaceDevelopmentContextGateway developmentContextGateway;
    @Mock private ScopedDocumentGateway documentGateway;
    @Mock private WorkbenchStageUploadedConversationAttachmentRepository
            stageAttachmentRepository;
    @Mock private RuntimePreflightGateway preflightGateway;
    @Mock private ChatRunIdGenerator runIdGenerator;
    @Mock private WorkbenchTelemetry telemetry;
    @Mock private WorkbenchRunPreparationSettings settings;
    @Mock private Clock clock;

    @InjectMocks
    private WorkbenchStageRunPreparationService service;

    private RepositoryScope repositoryScope;
    private Workbench workbench;
    private WorkbenchStageSnapshot stageSnapshot;
    private WorkspaceDevelopmentContext developmentContext;
    private WorkspaceSnapshot workspaceSnapshot;
    private ResolvedCapabilityResolution capabilityResolution;
    private WorkbenchContextManifest contextManifest;
    private SubmitWorkbenchStageRunCommand command;

    @BeforeEach
    void setUp() {
        repositoryScope = repositoryScope();
        stageSnapshot = stageSnapshot();
        workbench = workbench();
        developmentContext = developmentContext();
        workspaceSnapshot = workspaceSnapshot();
        capabilityResolution = capabilityResolution();
        contextManifest = WorkbenchContextManifest.freeze(
                WORKBENCH_ID, 0L, repeat('c'),
                Collections.emptyList(),
                "Context version: 0\nNo published documents.");
        command = new SubmitWorkbenchStageRunCommand(
                WORKBENCH_ID, STAGE_INSTANCE_ID, workbench.getVersion(),
                "stage-submission-1", "Finish the design.",
                RunMode.DISCUSS_READ_ONLY, Collections.emptyList());
    }

    @Test
    void should_PrepareExactDynamicStageSnapshotPromptAndRuntimeFacts() {
        // Given
        stubSuccessPath();

        // When
        PreparedWorkbenchStageRun prepared = service.prepare(OWNER, command);

        // Then
        assertSame(command, prepared.getCommand());
        assertEquals(RUN_ID, prepared.getSnapshot().getRunId());
        assertEquals(STAGE_INSTANCE_ID,
                prepared.getSnapshot().getStageInstanceIdentifier());
        assertEquals(stageSnapshot.getSnapshotHash(),
                prepared.getSnapshot().getStageSnapshotHash());
        assertEquals(0L, prepared.getSnapshot().getContextVersion());
        assertEquals(contextManifest.getContextHash(),
                prepared.getSnapshot().getContextHash());
        assertTrue(prepared.getSnapshot().getContextDocumentReferences()
                .isEmpty());
        assertSame(workspaceSnapshot, prepared.getWorkspaceSnapshot());
        assertEquals(prepared.getSnapshot().getPromptHash(),
                prepared.getPromptPayload().getPromptHash());
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains("## STAGE_DEFINITION"));
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains("## GLOBAL_CONTEXT"));
        assertTrue(prepared.getSnapshot().getPromptParts().stream()
                .anyMatch(part -> WorkbenchPromptPartType.STAGE_HISTORY.name()
                        .equals(part.getType())));

        ArgumentCaptor<RuntimePreflightRequest> preflightRequest =
                ArgumentCaptor.forClass(RuntimePreflightRequest.class);
        verify(preflightGateway).inspect(preflightRequest.capture());
        assertSame(capabilityResolution.getBinding(),
                preflightRequest.getValue().getCapabilityBinding());
        assertEquals(SandboxMode.READ_ONLY, preflightRequest.getValue()
                .getWorkspaceLayout().getSandboxMode());
        verify(telemetry).capabilityResolution("SUCCESS");
    }

    @Test
    void should_StopBeforeReadingWorkbenchWhenRuntimeIsUnavailable() {
        // Given
        org.mockito.Mockito.doThrow(new WorkbenchRunUnavailableException())
                .when(availability).requireAvailable(
                        RunMode.DISCUSS_READ_ONLY);

        // When / Then
        assertThrows(WorkbenchRunUnavailableException.class,
                () -> service.prepare(OWNER, command));
        verifyNoInteractions(workbenchRepository);
        verify(snapshotGateway, never()).capture(any(), any(), any());
        verify(preflightGateway, never()).inspect(any());
    }

    @Test
    void should_VerifyUploadedAttachmentAgainstExactDynamicStageBinding() {
        // Given
        stubSuccessPath();
        String contentHash = repeat('8');
        WorkbenchStageUploadedConversationAttachment attachment =
                WorkbenchStageUploadedConversationAttachment.upload(
                        "stage-attachment-1",
                        new WorkbenchStageUploadedAttachmentBinding(
                                OWNER, WORKBENCH_ID, STAGE_INSTANCE_ID,
                                SESSION_ID, 0),
                        "design.md", "text/markdown",
                        UploadedAttachmentContentSignature.TEXT,
                        64L, contentHash, repeat('9'),
                        UploadedAttachmentPolicy.standard(
                                1024L, 8, Duration.ofHours(24),
                                Duration.ofHours(2)), NOW);
        when(stageAttachmentRepository.findById("stage-attachment-1"))
                .thenReturn(Optional.of(attachment));
        SubmitWorkbenchStageRunCommand attachedCommand =
                new SubmitWorkbenchStageRunCommand(
                        WORKBENCH_ID, STAGE_INSTANCE_ID,
                        workbench.getVersion(), "stage-submission-upload",
                        "Review the attachment.",
                        RunMode.DISCUSS_READ_ONLY,
                        Collections.singletonList(
                                WorkbenchRunAttachmentReference
                                        .uploadedConversation(
                                                "stage-attachment-1",
                                                contentHash)));

        // When
        PreparedWorkbenchStageRun prepared = service.prepare(
                OWNER, attachedCommand);

        // Then
        assertEquals(1, prepared.getVerifiedAttachments()
                .getUploadedAttachments().size());
        assertEquals(STAGE_INSTANCE_ID, prepared.getVerifiedAttachments()
                .getUploadedAttachments().get(0).getBinding()
                .getStageInstanceIdentifier());
        assertEquals("stage-attachment-1", prepared.getSnapshot()
                .getVerifiedUploadedAttachments().get(0)
                .getAttachmentId());
        verify(stageAttachmentRepository).findById("stage-attachment-1");
    }

    private void stubSuccessPath() {
        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        ChatSession session = ChatSession.createWorkbenchStage(
                SESSION_ID, AgentType.CODEX,
                repositoryScope.primaryRepository().getRepositoryRoot(),
                contextId(), OWNER.getOwnerId(), OWNER.getOwnerName(),
                NOW.plusSeconds(2));
        session.setEnv("local");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(session);
        when(historyQuery.load(any())).thenReturn(
                WorkbenchStageConversationHistory.freeze(
                        SESSION_ID, contextId(), 0,
                        "assistant: previous Stage answer",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX));
        when(contextManifestQuery.load(any())).thenReturn(contextManifest);
        when(settings.getRuntimeCompatibility()).thenReturn(
                RUNTIME_COMPATIBILITY);
        when(capabilityResolver.resolve(
                same(stageSnapshot), eq(RunMode.DISCUSS_READ_ONLY),
                eq(AgentType.CODEX), eq(RUNTIME_COMPATIBILITY)))
                .thenReturn(capabilityResolution);
        when(capabilityResolver.resolveCommand(
                same(stageSnapshot), eq(null))).thenReturn(null);
        when(developmentContextGateway.inspect(same(repositoryScope)))
                .thenReturn(developmentContext);
        when(snapshotIdGenerator.nextId()).thenReturn("run-snapshot-1");
        when(snapshotGateway.capture(
                eq("run-snapshot-1"), same(repositoryScope), any()))
                .thenReturn(workspaceSnapshot);
        when(preflightGateway.inspect(any())).thenReturn(
                new RuntimePreflightReport(
                        AgentType.CODEX, "0.42.0",
                        "codex-workbench-stage@1", SandboxMode.READ_ONLY,
                        1, 0,
                        capabilityResolution.getBinding().getBindingHash()));
        when(settings.getRuntimeLimits()).thenReturn(
                new RuntimeLimits(Duration.ofMinutes(30), 8_388_608L));
        when(runIdGenerator.nextId()).thenReturn(ChatRunId.of(RUN_ID));
    }

    private Workbench workbench() {
        Workbench result = Workbench.create(
                WORKBENCH_ID, OWNER, "Dynamic Workbench",
                "Complete Dynamic Stage Run.", AgentType.CODEX, "local",
                repositoryScope, creationSnapshotReference(),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_INSTANCE_ID, stageSnapshot)), NOW);
        result.bindStageConversation(
                STAGE_INSTANCE_ID, SESSION_ID, OWNER,
                0L, NOW.plusSeconds(2));
        return result;
    }

    private WorkbenchStageSnapshot stageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft("solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "完成完整方案",
                        "Keep Dynamic Stage facts exact.",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()), administrator, NOW);
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        "solution-design", catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList()),
                        administrator, NOW.plusSeconds(1)));
    }

    private ResolvedCapabilityResolution capabilityResolution() {
        ResolvedRuleBinding rule = new ResolvedRuleBinding(
                "workbench/stage/solution-design", "1",
                "WORKBENCH_STAGE_SNAPSHOT",
                CanonicalHashing.sha256(stageSnapshot.getStageRules()),
                true, "Frozen Stage rules");
        ResolvedCapabilityBinding binding =
                ResolvedCapabilityBinding.resolve(
                        "workbench-stage-policy@1",
                        "workbench-stage/solution-design", "1",
                        stageSnapshot.getSnapshotHash(),
                        Collections.singletonList(rule),
                        Collections.<ResolvedSkillBinding>emptyList(),
                        Collections.<ResolvedMcpServerBinding>emptyList(),
                        Collections.<RejectedCapability>emptyList(),
                        RUNTIME_COMPATIBILITY);
        return ResolvedCapabilityResolution.of(
                binding, Collections.singletonList(
                        ResolvedCapabilityRuleContent.bind(
                                rule, stageSnapshot.getStageRules())));
    }

    private WorkspaceDevelopmentContext developmentContext() {
        return WorkspaceDevelopmentContext.create(
                repositoryScope.getScopeHash(), "agent-web",
                Collections.singletonList(
                        new RepositoryDevelopmentContextClassifier().classify(
                                "agent-web", EnumSet.of(
                                        RepositoryDevelopmentMarker.POM_XML))));
    }

    private WorkspaceSnapshot workspaceSnapshot() {
        WorkspaceTopology topology = WorkspaceTopology.of(
                "/workspace", RepositorySelection.of(
                        "agent-web", Collections.singletonList("agent-web")));
        return WorkspaceSnapshot.capture(
                "run-snapshot-1",
                SnapshotPurpose.of("WORKBENCH_RUN_START"), topology,
                Collections.singletonList(RepositoryBaseline.capture(
                        "agent-web", "/workspace/agent-web", "main",
                        String.join("", Collections.nCopies(40, "1")),
                        true, repeat('2'), NOW.plusSeconds(4))),
                Collections.emptyList(), NOW.plusSeconds(3),
                NOW.plusSeconds(4));
    }

    private RepositoryScope repositoryScope() {
        return RepositoryScope.create(
                "/workspace", RepositorySelection.of(
                        "agent-web", Collections.singletonList("agent-web")),
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('1'), false)), 10);
    }

    private WorkspaceSnapshotReference creationSnapshotReference() {
        return new WorkspaceSnapshotReference(
                "creation-snapshot",
                WorkspaceTopology.of(
                        "/workspace", RepositorySelection.of(
                                "agent-web",
                                Collections.singletonList("agent-web")))
                        .getTopologyHash(),
                repeat('a'), 1);
    }

    private String contextId() {
        return WORKBENCH_ID.getValue() + ":" + STAGE_INSTANCE_ID;
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, String.valueOf(value)));
    }
}
