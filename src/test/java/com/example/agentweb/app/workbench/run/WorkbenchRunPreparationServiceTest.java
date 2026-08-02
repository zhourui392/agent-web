package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunIdGenerator;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimePreflightErrorCode;
import com.example.agentweb.app.runtime.port.RuntimePreflightException;
import com.example.agentweb.app.runtime.port.RuntimePreflightGateway;
import com.example.agentweb.app.runtime.port.RuntimePreflightReport;
import com.example.agentweb.app.runtime.port.RuntimePreflightRequest;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.workbench.WorkspaceSnapshotIdGenerator;
import com.example.agentweb.app.workbench.document.DocumentContentView;
import com.example.agentweb.app.workbench.document.DocumentFailureCode;
import com.example.agentweb.app.workbench.document.DocumentKind;
import com.example.agentweb.app.workbench.document.DocumentOperationException;
import com.example.agentweb.app.workbench.document.port.ScopedDocumentGateway;
import com.example.agentweb.app.workbench.port.WorkspaceDevelopmentContextGateway;
import com.example.agentweb.app.workbench.port.WorkspaceSnapshotGateway;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HandoffReception;
import com.example.agentweb.domain.workbench.HandoffReceptionRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityBindingResolver;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfigurationRepository;
import com.example.agentweb.domain.workbench.PhaseCapabilityOverridePolicy;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfileCatalog;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityResolutionPolicy;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import com.example.agentweb.domain.workbench.PhaseHandoff;
import com.example.agentweb.domain.workbench.PhaseHandoffRepository;
import com.example.agentweb.domain.workbench.PhaseHandoffRevision;
import com.example.agentweb.domain.workbench.PhaseHandoffRevisionRepository;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.ResolvedCapabilityResolution;
import com.example.agentweb.domain.workbench.ResolvedCapabilityRuleContent;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmationRepository;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.ReviewOpinionRepository;
import com.example.agentweb.domain.workbench.RepositoryDevelopmentContextClassifier;
import com.example.agentweb.domain.workbench.RepositoryDevelopmentMarker;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchPhaseHistory;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchPromptPartType;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunAttachmentReference;
import com.example.agentweb.domain.workbench.WorkspaceDevelopmentContext;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.UploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.UploadedConversationAttachment;
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
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workbench Run 纵向准备流程测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunPreparationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T10:00:00Z");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-prepare-1");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final String SESSION_ID = "phase-session-1";
    private static final String RUN_ID = "run-prepared-1";
    private static final String CATALOG_RULE_CONTENT =
            "# Catalog Rule\n仅在授权仓库内核实事实😀";
    private static final String OWNER_RULE_CONTENT =
            "输出前逐项核对影响范围";
    private static final String RUNTIME_COMPATIBILITY =
            "m0-2026-07-22";

    private WorkbenchRepository workbenchRepository;
    private WorkbenchRunAvailability availability;
    private SessionRepository sessionRepository;
    private WorkbenchPhaseHistoryQuery historyQuery;
    private PhaseCapabilityProfileCatalog profileCatalog;
    private PhaseCapabilityConfigurationRepository configurationRepository;
    private PhaseCapabilityBindingResolver capabilityBindingResolver;
    private WorkspaceSnapshotIdGenerator snapshotIdGenerator;
    private WorkspaceSnapshotGateway snapshotGateway;
    private WorkspaceDevelopmentContextGateway developmentContextGateway;
    private ScopedDocumentGateway documentGateway;
    private UploadedConversationAttachmentRepository attachmentRepository;
    private RuntimePreflightGateway preflightGateway;
    private PhaseHandoffRevisionRepository revisionRepository;
    private PhaseHandoffRepository handoffRepository;
    private HandoffReceptionRepository receptionRepository;
    private ReviewModifyConfirmationRepository confirmationRepository;
    private ReviewOpinionRepository opinionRepository;
    private ChatRunIdGenerator runIdGenerator;
    private WorkbenchTelemetry telemetry;
    private WorkbenchRunPreparationService service;
    private RepositoryScope scope;
    private Workbench workbench;
    private ResolvedCapabilityBinding binding;
    private WorkspaceSnapshot runStartSnapshot;
    private WorkspaceDevelopmentContext developmentContext;
    private SubmitWorkbenchRunCommand command;

    @BeforeEach
    void setUp() {
        availability = mock(WorkbenchRunAvailability.class);
        workbenchRepository = mock(WorkbenchRepository.class);
        sessionRepository = mock(SessionRepository.class);
        historyQuery = mock(WorkbenchPhaseHistoryQuery.class);
        profileCatalog = mock(PhaseCapabilityProfileCatalog.class);
        configurationRepository = mock(
                PhaseCapabilityConfigurationRepository.class);
        capabilityBindingResolver = mock(
                PhaseCapabilityBindingResolver.class);
        snapshotIdGenerator = mock(WorkspaceSnapshotIdGenerator.class);
        snapshotGateway = mock(WorkspaceSnapshotGateway.class);
        developmentContextGateway = mock(
                WorkspaceDevelopmentContextGateway.class);
        documentGateway = mock(ScopedDocumentGateway.class);
        attachmentRepository = mock(
                UploadedConversationAttachmentRepository.class);
        preflightGateway = mock(RuntimePreflightGateway.class);
        revisionRepository = mock(PhaseHandoffRevisionRepository.class);
        handoffRepository = mock(PhaseHandoffRepository.class);
        receptionRepository = mock(HandoffReceptionRepository.class);
        confirmationRepository = mock(
                ReviewModifyConfirmationRepository.class);
        opinionRepository = mock(ReviewOpinionRepository.class);
        runIdGenerator = mock(ChatRunIdGenerator.class);
        telemetry = mock(WorkbenchTelemetry.class);
        service = new WorkbenchRunPreparationService(
                availability, workbenchRepository, sessionRepository, historyQuery,
                profileCatalog, configurationRepository,
                capabilityBindingResolver, snapshotIdGenerator,
                snapshotGateway, developmentContextGateway,
                documentGateway, attachmentRepository, preflightGateway,
                handoffRepository, revisionRepository, receptionRepository,
                confirmationRepository, opinionRepository, runIdGenerator,
                telemetry,
                new WorkbenchRunPreparationSettings(
                        "workbench-policy@1",
                        RUNTIME_COMPATIBILITY,
                        Collections.singleton(SkillTrustSource.PLATFORM),
                        new RuntimeLimits(
                                Duration.ofMinutes(30), 8_388_608L,
                                Collections.<String>emptySet())),
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));

        scope = scope();
        workbench = workbench(WorkbenchPhase.REQUIREMENT_ANALYSIS);
        binding = binding();
        runStartSnapshot = snapshot(
                "run-start-snapshot", SnapshotPurpose.of(
                        "WORKBENCH_RUN_START"));
        developmentContext = WorkspaceDevelopmentContext.create(
                scope.getScopeHash(), scope.getPrimaryRepositoryKey(),
                Collections.singletonList(
                        new RepositoryDevelopmentContextClassifier().classify(
                                "agent-web", EnumSet.of(
                                        RepositoryDevelopmentMarker.POM_XML,
                                        RepositoryDevelopmentMarker.README_MARKDOWN))));
        command = command(
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                RunMode.DISCUSS_READ_ONLY, null);
        stubCommonPreparation();
    }

    @Test
    void disabledCommonRuntimeShouldFailBeforeAnyPreparationSideEffect() {
        org.mockito.Mockito.doThrow(new WorkbenchRunUnavailableException())
                .when(availability).requireAvailable(
                        RunMode.DISCUSS_READ_ONLY);

        assertThrows(WorkbenchRunUnavailableException.class,
                () -> service.prepare(OWNER, command));

        verifyNoInteractions(
                workbenchRepository, sessionRepository, historyQuery,
                profileCatalog, configurationRepository,
                capabilityBindingResolver, snapshotIdGenerator,
                snapshotGateway, developmentContextGateway,
                documentGateway, preflightGateway,
                revisionRepository, receptionRepository,
                confirmationRepository, opinionRepository,
                runIdGenerator);
        verify(availability).requireAvailable(
                RunMode.DISCUSS_READ_ONLY);
        verifyNoInteractions(telemetry);
    }

    @Test
    void prepareShouldFreezeExactPayloadSnapshotHashesAndUtf8PartSizes() {
        PreparedWorkbenchRun prepared = service.prepare(OWNER, command);

        assertSame(command, prepared.getCommand());
        assertEquals(RUN_ID, prepared.getSnapshot().getRunId());
        assertEquals(RUN_ID, prepared.getPromptPayload().getRunId());
        assertEquals(prepared.getPromptPayload().getPromptHash(),
                prepared.getSnapshot().getPromptHash());
        assertEquals(prepared.getPromptPayload().getCreatedAt(),
                prepared.getSnapshot().getCreatedAt());
        assertSame(binding,
                prepared.getSnapshot().getCapabilityBinding());
        assertSame(runStartSnapshot,
                prepared.getWorkspaceSnapshot());
        prepared.getSnapshot().requireWorkspaceSnapshot(
                prepared.getWorkspaceSnapshot());
        assertEquals(runStartSnapshot.reference(),
                prepared.getSnapshot().getWorkspaceSnapshotReference());
        assertNull(prepared.getHandoffReception());
        assertNull(prepared.getSnapshot().getHandoffSource());
        assertFalse(prepared.getSnapshot().getPromptParts().stream()
                .anyMatch(part -> WorkbenchPromptPartType.UPSTREAM_HANDOFF
                        .name().equals(part.getType())));
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains("历史😀"));
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains("## ORIGINAL_GOAL\n" + workbench.getOriginalGoal()));
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains("Development context hash: "
                        + developmentContext.getContextHash()));
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains("technologies=JAVA buildTools=MAVEN"));
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains("instruction=OVERVIEW:README.md"));
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains(command.getMessage()));
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains(CATALOG_RULE_CONTENT));
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains(OWNER_RULE_CONTENT));
        for (ResolvedRuleBinding rule
                : prepared.getSnapshot().getCapabilityBinding().getRules()) {
            String content = "WORKBENCH_OWNER_OVERRIDE".equals(
                    rule.getSource())
                    ? OWNER_RULE_CONTENT : CATALOG_RULE_CONTENT;
            assertEquals(CanonicalHashing.sha256(content),
                    rule.getContentHash());
        }

        for (PromptPartSnapshot part
                : prepared.getSnapshot().getPromptParts()) {
            String content = promptPartContent(
                    prepared.getPromptPayload().getFinalPrompt(),
                    WorkbenchPromptPartType.valueOf(part.getType()));
            assertEquals(CanonicalHashing.sha256(content),
                    part.getContentHash());
            assertEquals(content.getBytes(StandardCharsets.UTF_8).length,
                    part.getContentSize());
        }
        PromptPartSnapshot originalGoalSnapshot = prepared.getSnapshot()
                .getPromptParts().stream()
                .filter(part -> WorkbenchPromptPartType.ORIGINAL_GOAL.name()
                        .equals(part.getType()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("workbench/original-goal@1",
                originalGoalSnapshot.getSource());
        assertEquals(CanonicalHashing.sha256(workbench.getOriginalGoal()),
                originalGoalSnapshot.getContentHash());
        assertEquals(workbench.getOriginalGoal()
                        .getBytes(StandardCharsets.UTF_8).length,
                originalGoalSnapshot.getContentSize());
        String prompt = prepared.getPromptPayload().getFinalPrompt();
        assertTrue(prompt.indexOf("## WORKSPACE_CONTEXT\n")
                < prompt.indexOf("## ORIGINAL_GOAL\n"));
        assertTrue(prompt.indexOf("## ORIGINAL_GOAL\n")
                < prompt.indexOf("## PHASE_HISTORY\n"));
        assertTrue(prompt.indexOf("## ORIGINAL_GOAL\n")
                < prompt.indexOf("## USER_INPUT\n"));
        assertFalse(prepared.getSnapshot().getPromptParts().isEmpty());
        verify(historyQuery).load(any());
        verify(developmentContextGateway).inspect(same(scope));
        ArgumentCaptor<SnapshotPurpose> purpose =
                ArgumentCaptor.forClass(SnapshotPurpose.class);
        verify(snapshotGateway).capture(
                eq("run-start-snapshot"), eq(scope), purpose.capture());
        assertEquals("WORKBENCH_RUN_START",
                purpose.getValue().getValue());
        ArgumentCaptor<RuntimePreflightRequest> preflight =
                ArgumentCaptor.forClass(RuntimePreflightRequest.class);
        verify(preflightGateway).inspect(preflight.capture());
        assertSame(binding,
                preflight.getValue().getCapabilityBinding());
        assertEquals(SandboxMode.READ_ONLY,
                preflight.getValue().getWorkspaceLayout().getSandboxMode());
        assertEquals(Collections.singletonList("/workspace/agent-web"),
                preflight.getValue().getWorkspaceLayout().getReadableRoots());
        assertTrue(preflight.getValue().getWorkspaceLayout()
                .getWritableRoots().isEmpty());
        assertEquals("0.42.0", prepared.getSnapshot()
                .getRuntimeEnforcement().getRuntimeVersion());
        assertEquals(1800L, prepared.getSnapshot()
                .getRuntimeEnforcement().getTimeoutSeconds());
    }

    @Test
    void prepareShouldReadExactAttachmentFromFrozenScopeAndKeepVerifiedFact() {
        DocumentReference reference = DocumentReference.of(
                "agent-web", "docs/design.md");
        WorkbenchRunAttachmentReference attachment =
                WorkbenchRunAttachmentReference.of(
                        reference.getRepositoryKey(),
                        reference.getRelativePath(), repeat('a'));
        command = command(Collections.singletonList(attachment));
        when(documentGateway.readContent(same(scope), eq(reference)))
                .thenReturn(content(reference, repeat('a'), false));

        PreparedWorkbenchRun prepared = service.prepare(OWNER, command);

        assertEquals(1, prepared.getVerifiedAttachments().size());
        assertEquals(reference, prepared.getVerifiedAttachments().get(0)
                .getDocumentReference());
        assertEquals(repeat('a'), prepared.getVerifiedAttachments().get(0)
                .getContentVersion());
        assertEquals("text/markdown", prepared.getVerifiedAttachments().get(0)
                .getMediaType());
        assertEquals(10L, prepared.getVerifiedAttachments().get(0).getSize());
        assertEquals(prepared.getVerifiedAttachments(),
                prepared.getSnapshot().getVerifiedAttachments());
        String prompt = prepared.getPromptPayload().getFinalPrompt();
        String attachmentContent = promptPartContent(
                prompt, WorkbenchPromptPartType.ATTACHMENTS);
        assertEquals("- repositoryKey=agent-web"
                        + " relativePath=docs/design.md"
                        + " contentHash=" + repeat('a')
                        + " mediaType=text/markdown size=10",
                attachmentContent);
        assertFalse(attachmentContent.contains("/workspace/"));
        PromptPartSnapshot attachmentSnapshot = prepared.getSnapshot()
                .getPromptParts().stream()
                .filter(part -> WorkbenchPromptPartType.ATTACHMENTS.name()
                        .equals(part.getType()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("workbench/attachments@1",
                attachmentSnapshot.getSource());
        assertEquals(CanonicalHashing.sha256(attachmentContent),
                attachmentSnapshot.getContentHash());
        assertEquals(attachmentContent.getBytes(StandardCharsets.UTF_8).length,
                attachmentSnapshot.getContentSize());
        assertTrue(prompt.indexOf("## ORIGINAL_GOAL\n")
                < prompt.indexOf("## ATTACHMENTS\n"));
        assertTrue(prompt.indexOf("## ATTACHMENTS\n")
                < prompt.indexOf("## PHASE_HISTORY\n"));
        assertThrows(UnsupportedOperationException.class,
                () -> prepared.getVerifiedAttachments().clear());
        verify(documentGateway).readContent(same(scope), eq(reference));
    }

    @Test
    void prepareShouldVerifyUploadedAttachmentWithoutReadingRepositoryPath() {
        UploadedConversationAttachment uploaded =
                UploadedConversationAttachment.upload(
                        "attachment-1",
                        new UploadedAttachmentBinding(
                                OWNER, WORKBENCH_ID,
                                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                SESSION_ID, 0),
                        "browser-design.md", "text/markdown",
                        UploadedAttachmentContentSignature.TEXT,
                        64L, repeat('7'), repeat('8'),
                        UploadedAttachmentPolicy.standard(
                                1024L, 16, Duration.ofHours(24),
                                Duration.ofHours(2)), NOW.plusSeconds(1));
        when(attachmentRepository.findById("attachment-1"))
                .thenReturn(Optional.of(uploaded));
        command = command(Collections.singletonList(
                WorkbenchRunAttachmentReference.uploadedConversation(
                        "attachment-1", repeat('7'))));

        PreparedWorkbenchRun prepared = service.prepare(OWNER, command);

        assertEquals(1, prepared.getVerifiedUploadedAttachments().size());
        assertEquals("attachment-1",
                prepared.getVerifiedUploadedAttachments().get(0)
                        .getAttachmentId());
        assertEquals(prepared.getVerifiedUploadedAttachments(),
                prepared.getSnapshot().getVerifiedUploadedAttachments());
        String attachmentContent = promptPartContent(
                prepared.getPromptPayload().getFinalPrompt(),
                WorkbenchPromptPartType.ATTACHMENTS);
        assertTrue(attachmentContent.contains("type=UPLOADED_CONVERSATION"));
        assertTrue(attachmentContent.contains(
                "$AGENT_WORKBENCH_ATTACHMENT_DIR/attachment-"));
        assertFalse(attachmentContent.contains(repeat('8')));
        assertFalse(attachmentContent.contains("repositoryKey="));
        verifyNoInteractions(documentGateway);
    }

    @Test
    void prepareShouldRejectAttachmentChangedSinceClientObservation() {
        DocumentReference reference = DocumentReference.of(
                "agent-web", "docs/design.md");
        command = command(Collections.singletonList(
                WorkbenchRunAttachmentReference.of(
                        "agent-web", "docs/design.md", repeat('a'))));
        when(documentGateway.readContent(same(scope), eq(reference)))
                .thenReturn(content(reference, repeat('b'), false));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.prepare(OWNER, command));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT,
                failure.getCode());
        verify(snapshotGateway, never()).capture(any(), any(), any());
        verify(preflightGateway, never()).inspect(any());
        verify(runIdGenerator, never()).nextId();
    }

    @Test
    void prepareShouldRejectUnexpectedObservedAttachmentReference() {
        DocumentReference requested = DocumentReference.of(
                "agent-web", "docs/design.md");
        DocumentReference observed = DocumentReference.of(
                "agent-web", "docs/other.md");
        command = command(Collections.singletonList(
                WorkbenchRunAttachmentReference.of(
                        "agent-web", "docs/design.md", repeat('a'))));
        when(documentGateway.readContent(same(scope), eq(requested)))
                .thenReturn(content(observed, repeat('a'), false));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.prepare(OWNER, command));

        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                failure.getCode());
        verify(snapshotGateway, never()).capture(any(), any(), any());
        verify(preflightGateway, never()).inspect(any());
    }

    @Test
    void prepareShouldRejectAttachmentDeletedDuringTrustedRead() {
        DocumentReference reference = DocumentReference.of(
                "agent-web", "docs/design.md");
        command = command(Collections.singletonList(
                WorkbenchRunAttachmentReference.of(
                        "agent-web", "docs/design.md", repeat('a'))));
        when(documentGateway.readContent(same(scope), eq(reference)))
                .thenReturn(content(reference, repeat('a'), true));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.prepare(OWNER, command));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT,
                failure.getCode());
        verify(snapshotGateway, never()).capture(any(), any(), any());
        verify(preflightGateway, never()).inspect(any());
    }

    @Test
    void prepareShouldPropagateScopedDocumentFailureBeforeRuntimePreparation() {
        DocumentReference reference = DocumentReference.of(
                "agent-web", "data/secrets.properties");
        command = command(Collections.singletonList(
                WorkbenchRunAttachmentReference.of(
                        "agent-web", "data/secrets.properties", repeat('a'))));
        DocumentOperationException unavailable =
                new DocumentOperationException(
                        DocumentFailureCode.WORKBENCH_DOCUMENT_NOT_FOUND,
                        "document is not available in the scoped repository");
        when(documentGateway.readContent(same(scope), eq(reference)))
                .thenThrow(unavailable);

        assertSame(unavailable, assertThrows(
                DocumentOperationException.class,
                () -> service.prepare(OWNER, command)));
        verify(snapshotGateway, never()).capture(any(), any(), any());
        verify(preflightGateway, never()).inspect(any());
        verify(runIdGenerator, never()).nextId();
    }

    @Test
    void prepareShouldResolveCapabilityAgainstRuntimeCompatibilityMatrix() {
        service.prepare(OWNER, command);

        ArgumentCaptor<PhaseCapabilityResolutionPolicy> policy =
                ArgumentCaptor.forClass(
                        PhaseCapabilityResolutionPolicy.class);
        verify(capabilityBindingResolver).resolveForRun(
                any(), any(), policy.capture());
        assertEquals(RUNTIME_COMPATIBILITY,
                policy.getValue().getRuntimeCompatibility());
        verify(telemetry).capabilityResolution("SUCCESS");
    }

    @Test
    void prepareShouldIgnoreExpiredOverrideAndFreezeCurrentProfileDefault() {
        PhaseCapabilityProfile currentProfile = profile(
                WorkbenchPhase.REQUIREMENT_ANALYSIS);
        CapabilityOverride staleOverride =
                CapabilityOverride.withExplicitOptionalMcpSelection(
                        Collections.<String>emptySet(),
                        Collections.<String>emptySet(),
                        Collections.singleton("retired-query"),
                        Collections.<String>emptySet(), null);
        PhaseCapabilityOverridePolicy oldPolicy =
                PhaseCapabilityOverridePolicy.constrainedTo(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        Collections.<String>emptySet(),
                        Collections.singleton("retired-query"),
                        Collections.<String>emptySet(),
                        Collections.<String>emptySet());
        PhaseCapabilityConfiguration staleConfiguration =
                PhaseCapabilityConfiguration.restore(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        currentProfile.getProfileId(), "0",
                        staleOverride, OWNER, NOW, 9L, oldPolicy);
        when(profileCatalog.requireProfile(
                WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(currentProfile);
        when(configurationRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(staleConfiguration));

        PreparedWorkbenchRun prepared = service.prepare(OWNER, command);

        ArgumentCaptor<CapabilityOverride> effectiveOverride =
                ArgumentCaptor.forClass(CapabilityOverride.class);
        verify(capabilityBindingResolver).resolveForRun(
                eq(currentProfile), effectiveOverride.capture(), any());
        assertTrue(effectiveOverride.getValue()
                .getSelectedOptionalMcpIds().isEmpty());
        assertFalse(effectiveOverride.getValue()
                .hasExplicitOptionalMcpSelection());
        assertEquals(Long.valueOf(9L),
                prepared.getSnapshot().getOverrideVersion());
    }

    @Test
    void prepareShouldRejectHistoryFromAnotherPhaseSessionBeforeSnapshot() {
        when(historyQuery.load(any())).thenReturn(
                WorkbenchPhaseHistory.freeze(
                        "foreign-session", "foreign:phase", "历史",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.prepare(OWNER, command));

        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                failure.getCode());
        verify(snapshotGateway, never()).capture(any(), any(), any());
        verify(preflightGateway, never()).inspect(any());
    }

    @Test
    void prepareShouldRejectMissingCurrentPhaseSessionBeforeHistory() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.prepare(OWNER, command));

        verifyNoInteractions(historyQuery);
        verify(snapshotGateway, never()).capture(any(), any(), any());
        verify(preflightGateway, never()).inspect(any());
    }

    @Test
    void prepareShouldFailClosedBeforeCandidateWhenCapabilityResolutionFails() {
        when(capabilityBindingResolver.resolveForRun(any(), any(), any()))
                .thenThrow(new IllegalStateException(
                        "required capability is unavailable"));

        assertThrows(IllegalStateException.class,
                () -> service.prepare(OWNER, command));

        verify(telemetry).capabilityResolution("FAILED");
        verify(snapshotGateway, never()).capture(any(), any(), any());
        verify(preflightGateway, never()).inspect(any());
    }

    @Test
    void prepareShouldPropagatePreflightFailureWithoutReturningCandidate() {
        when(preflightGateway.inspect(any())).thenThrow(
                new RuntimePreflightException(
                        RuntimePreflightErrorCode.RUNTIME_PROBE_FAILED,
                        "runtime preflight failed"));

        assertThrows(RuntimePreflightException.class,
                () -> service.prepare(OWNER, command));

        verify(snapshotGateway).capture(any(), any(), any());
    }

    @Test
    void prepareShouldStopBeforePreflightWhenWorkspaceCaptureFails() {
        when(snapshotGateway.capture(any(), any(), any()))
                .thenThrow(new IllegalStateException(
                        "workspace capture failed"));

        assertThrows(IllegalStateException.class,
                () -> service.prepare(OWNER, command));

        verify(preflightGateway, never()).inspect(any());
    }

    @Test
    void reviewModifyShouldRejectMessageDifferentFromExactConfirmedOpinionBeforeSideEffects() {
        workbench = workbench(WorkbenchPhase.REVIEW_REFACTOR);
        ReviewOpinion opinion = ReviewOpinion.start(
                WORKBENCH_ID, 0L, "只重构已确认的缓存边界",
                OWNER, NOW);
        ReviewModifyConfirmation confirmation = opinion.confirmModify(
                "review-confirmation-1", opinion.getVersion(),
                opinion.getContentHash(), OWNER, NOW.plusSeconds(1));
        command = new SubmitWorkbenchRunCommand(
                WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                workbench.getVersion(), "review-submission-1",
                "改成另一套未确认的并发模型", RunMode.MODIFY_WORKSPACE,
                Long.valueOf(0L), confirmation.getConfirmationId(),
                Collections.emptyList());
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        ChatSession reviewSession = ChatSession.createWorkbenchPhase(
                SESSION_ID, AgentType.CODEX,
                scope.primaryRepository().getRepositoryRoot(),
                context(WorkbenchPhase.REVIEW_REFACTOR),
                OWNER.getOwnerId(), OWNER.getOwnerName(),
                NOW.plusSeconds(1));
        reviewSession.setEnv("local");
        when(sessionRepository.findById(SESSION_ID))
                .thenReturn(reviewSession);
        when(historyQuery.load(any())).thenReturn(
                WorkbenchPhaseHistory.freeze(
                        SESSION_ID,
                        context(WorkbenchPhase.REVIEW_REFACTOR), "",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX));
        when(confirmationRepository.findById(
                confirmation.getConfirmationId()))
                .thenReturn(Optional.of(confirmation));
        when(opinionRepository.find(
                WORKBENCH_ID, opinion.getVersion()))
                .thenReturn(Optional.of(opinion));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.prepare(OWNER, command));

        assertEquals(WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                failure.getCode());
        verify(profileCatalog, never()).requireProfile(any());
        verify(snapshotGateway, never()).capture(any(), any(), any());
        verify(preflightGateway, never()).inspect(any());
        verifyNoInteractions(runIdGenerator);
    }

    @Test
    void prepareShouldCreateInitialReceptionFromCurrentExactHandoff() {
        PhaseHandoff latest = configureSolutionPhase();
        PhaseHandoffRevision revision = PhaseHandoffRevision.capture(latest);
        when(receptionRepository.find(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.empty());
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(latest));
        when(revisionRepository.findExact(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                latest.getVersion(), latest.getContentHash()))
                .thenReturn(Optional.of(revision));

        PreparedWorkbenchRun prepared = service.prepare(OWNER, command);

        HandoffReception reception = prepared.getHandoffReception();
        assertEquals(WORKBENCH_ID, reception.getWorkbenchId());
        assertEquals(WorkbenchPhase.SOLUTION_DESIGN,
                reception.getTargetPhase());
        assertEquals(0L, reception.getSourceVersion());
        assertEquals(latest.getContentHash(), reception.getSourceHash());
        assertEquals(reception.getSourceVersion(), prepared.getSnapshot()
                .getHandoffSource().getSourceVersion());
        assertEquals(reception.getSourceHash(), prepared.getSnapshot()
                .getHandoffSource().getSourceHash());
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains("人工确认的需求交接😀"));
        verify(receptionRepository, never()).save(any());
    }

    @Test
    void prepareShouldRejectInitialReceptionWhenLatestVersionChanged() {
        PhaseHandoff latest = configureSolutionPhase();
        latest.update(
                0L, "预览后变化的需求交接",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                scope, OWNER, NOW.plusSeconds(2));
        when(receptionRepository.find(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.empty());
        when(handoffRepository.find(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(latest));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.prepare(OWNER, command));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT,
                failure.getCode());
        verifyNoInteractions(revisionRepository);
        verify(snapshotGateway, never()).capture(any(), any(), any());
        verify(preflightGateway, never()).inspect(any());
        verify(runIdGenerator, never()).nextId();
    }

    @Test
    void prepareShouldKeepAcceptedExactRevisionWhenLatestHandoffChanges() {
        PhaseHandoff latest = configureSolutionPhase();
        PhaseHandoffRevision acceptedRevision =
                PhaseHandoffRevision.capture(latest);
        HandoffReception accepted = HandoffReception.accept(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, 0L,
                latest.getContentHash(), OWNER, NOW.plusSeconds(1));
        latest.update(
                0L, "更新后的交接，不得静默注入",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                scope, OWNER, NOW.plusSeconds(2));
        when(receptionRepository.find(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(accepted));
        when(revisionRepository.findExact(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                0L, accepted.getSourceHash()))
                .thenReturn(Optional.of(acceptedRevision));

        PreparedWorkbenchRun prepared = service.prepare(OWNER, command);

        assertSame(accepted, prepared.getHandoffReception());
        assertTrue(prepared.getPromptPayload().getFinalPrompt()
                .contains("人工确认的需求交接😀"));
        assertFalse(prepared.getPromptPayload().getFinalPrompt()
                .contains("更新后的交接，不得静默注入"));
    }

    @Test
    void prepareShouldFailClosedBeforeWorkspaceWhenExactHandoffRevisionMissing() {
        PhaseHandoff source = configureSolutionPhase();
        HandoffReception accepted = HandoffReception.accept(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, 0L,
                source.getContentHash(), OWNER, NOW.plusSeconds(1));
        when(receptionRepository.find(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS))
                .thenReturn(Optional.of(accepted));
        when(revisionRepository.findExact(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                0L, accepted.getSourceHash()))
                .thenReturn(Optional.empty());

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> service.prepare(OWNER, command));

        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                failure.getCode());
        verify(snapshotGateway, never()).capture(any(), any(), any());
        verify(preflightGateway, never()).inspect(any());
    }

    private void stubCommonPreparation() {
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        ChatSession session = ChatSession.createWorkbenchPhase(
                SESSION_ID, AgentType.CODEX,
                scope.primaryRepository().getRepositoryRoot(),
                context(WorkbenchPhase.REQUIREMENT_ANALYSIS),
                OWNER.getOwnerId(), OWNER.getOwnerName(),
                NOW.plusSeconds(1));
        session.setEnv("local");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(session);
        when(historyQuery.load(any())).thenReturn(
                WorkbenchPhaseHistory.freeze(
                        SESSION_ID,
                        context(WorkbenchPhase.REQUIREMENT_ANALYSIS),
                        "assistant: 历史😀",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX));
        when(profileCatalog.requireProfile(any())).thenReturn(
                profile(WorkbenchPhase.REQUIREMENT_ANALYSIS));
        when(configurationRepository.find(any(), any()))
                .thenReturn(Optional.empty());
        when(capabilityBindingResolver.resolveForRun(any(), any(), any()))
                .thenReturn(capabilityResolution());
        when(snapshotIdGenerator.nextId())
                .thenReturn("run-start-snapshot");
        when(snapshotGateway.capture(any(), any(), any()))
                .thenReturn(runStartSnapshot);
        when(developmentContextGateway.inspect(same(scope)))
                .thenReturn(developmentContext);
        when(preflightGateway.inspect(any())).thenReturn(
                new RuntimePreflightReport(
                        AgentType.CODEX, "0.42.0",
                        "codex-workbench@1", SandboxMode.READ_ONLY,
                        1, 0, binding.getBindingHash()));
        when(runIdGenerator.nextId())
                .thenReturn(ChatRunId.of(RUN_ID));
    }

    private Workbench workbench(WorkbenchPhase phase) {
        Workbench result = Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "实现本地工作台",
                AgentType.CODEX, "local", scope,
                creationSnapshotReference(), NOW);
        result.bindConversation(
                phase, SESSION_ID, OWNER, NOW.plusSeconds(1));
        return result;
    }

    private PhaseHandoff configureSolutionPhase() {
        workbench = workbench(WorkbenchPhase.SOLUTION_DESIGN);
        command = command(
                WorkbenchPhase.SOLUTION_DESIGN,
                RunMode.DISCUSS_READ_ONLY, Long.valueOf(0L));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        ChatSession session = ChatSession.createWorkbenchPhase(
                SESSION_ID, AgentType.CODEX,
                scope.primaryRepository().getRepositoryRoot(),
                context(WorkbenchPhase.SOLUTION_DESIGN),
                OWNER.getOwnerId(), OWNER.getOwnerName(),
                NOW.plusSeconds(1));
        session.setEnv("local");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(session);
        when(historyQuery.load(any())).thenReturn(
                WorkbenchPhaseHistory.freeze(
                        SESSION_ID, context(WorkbenchPhase.SOLUTION_DESIGN),
                        "assistant: 当前设计历史",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX));
        when(profileCatalog.requireProfile(
                WorkbenchPhase.SOLUTION_DESIGN)).thenReturn(
                profile(WorkbenchPhase.SOLUTION_DESIGN));
        return PhaseHandoff.create(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "人工确认的需求交接😀",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), scope,
                OWNER, NOW.plusSeconds(1));
    }

    private SubmitWorkbenchRunCommand command(
            WorkbenchPhase phase, RunMode runMode,
            Long handoffSourceVersion) {
        return new SubmitWorkbenchRunCommand(
                WORKBENCH_ID, phase, workbench.getVersion(),
                "submission-key-1", "请核实需求边界😀", runMode,
                handoffSourceVersion, null, Collections.emptyList());
    }

    private SubmitWorkbenchRunCommand command(
            List<WorkbenchRunAttachmentReference> attachments) {
        return new SubmitWorkbenchRunCommand(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                workbench.getVersion(), "submission-key-1",
                "请核实需求边界😀", RunMode.DISCUSS_READ_ONLY,
                null, null, attachments);
    }

    private static DocumentContentView content(
            DocumentReference reference, String contentVersion,
            boolean deleted) {
        return new DocumentContentView(
                reference, DocumentKind.MARKDOWN, "text/markdown",
                "UTF-8", 10L, NOW.toEpochMilli(), contentVersion,
                "attachment content", false, deleted);
    }

    private static RepositoryScope scope() {
        return RepositoryScope.create(
                "/workspace",
                RepositorySelection.of(
                        "agent-web",
                        Collections.singletonList("agent-web")),
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('1'), false)),
                10);
    }

    private WorkspaceSnapshotReference creationSnapshotReference() {
        return new WorkspaceSnapshotReference(
                "creation-snapshot",
                WorkspaceTopology.of(
                        scope.getWorkspaceRoot(),
                        RepositorySelection.of(
                                "agent-web",
                                Collections.singletonList("agent-web")))
                        .getTopologyHash(),
                repeat('2'), 1);
    }

    private WorkspaceSnapshot snapshot(
            String snapshotId, SnapshotPurpose purpose) {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        WorkspaceTopology topology = WorkspaceTopology.of(
                scope.getWorkspaceRoot(), selection);
        RepositoryBaseline baseline = RepositoryBaseline.capture(
                "agent-web", "/workspace/agent-web", "master",
                repeat('a', 40), true, repeat('3'),
                NOW.plusSeconds(2));
        return WorkspaceSnapshot.capture(
                snapshotId, purpose, topology,
                Collections.singletonList(baseline),
                Collections.emptyList(), NOW.plusSeconds(1),
                NOW.plusSeconds(2));
    }

    private static PhaseCapabilityProfile profile(WorkbenchPhase phase) {
        return PhaseCapabilityProfile.create(
                phase.name().toLowerCase(), "1", phase,
                Collections.singletonList(
                        new PhaseCapabilityReference(
                                "platform/safety", PhaseCapabilityType.RULE,
                                true)));
    }

    private static ResolvedCapabilityBinding binding() {
        String ownerContentHash = CanonicalHashing.sha256(
                OWNER_RULE_CONTENT);
        return ResolvedCapabilityBinding.resolve(
                "workbench-policy@1", "requirement-analysis", "1",
                repeat('4'),
                Arrays.asList(
                        new ResolvedRuleBinding(
                                "platform/safety", "1", "PLATFORM",
                                CanonicalHashing.sha256(
                                        CATALOG_RULE_CONTENT), true,
                                "遵守平台与仓库边界"),
                        new ResolvedRuleBinding(
                                "workbench/owner-override/"
                                        + ownerContentHash,
                                "1", "WORKBENCH_OWNER_OVERRIDE",
                                ownerContentHash, false,
                                "Owner-provided Workbench phase preference")),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), RUNTIME_COMPATIBILITY);
    }

    private ResolvedCapabilityResolution capabilityResolution() {
        return ResolvedCapabilityResolution.of(
                binding,
                Arrays.asList(
                        ResolvedCapabilityRuleContent.bind(
                                binding.getRules().get(0),
                                CATALOG_RULE_CONTENT),
                        ResolvedCapabilityRuleContent.bind(
                                binding.getRules().get(1),
                                OWNER_RULE_CONTENT)));
    }

    private static String context(WorkbenchPhase phase) {
        return WORKBENCH_ID.getValue() + ":" + phase.name();
    }

    private static String promptPartContent(
            String prompt, WorkbenchPromptPartType type) {
        String marker = "## " + type.name() + "\n";
        int start = prompt.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("missing prompt part: " + type);
        }
        int contentStart = start + marker.length();
        int next = prompt.indexOf("\n\n## ", contentStart);
        return next < 0
                ? prompt.substring(contentStart)
                : prompt.substring(contentStart, next);
    }

    private static String repeat(char value) {
        return repeat(value, 64);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
