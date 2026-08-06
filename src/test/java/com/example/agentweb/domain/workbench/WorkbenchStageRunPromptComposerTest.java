package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.context.WorkbenchContextDocumentContentState;
import com.example.agentweb.domain.workbench.context.WorkbenchContextDocumentSnapshot;
import com.example.agentweb.domain.workbench.context.WorkbenchContextManifest;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryBaseline;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dynamic Stage Prompt 只组合 Stage、全局 Context 和当前会话事实。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchStageRunPromptComposerTest {

    private static final Instant NOW = Instant.parse("2026-08-05T13:00:00Z");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");

    @Test
    void should_ComposeFrozenStageContextAndHistoryWithoutLegacyParts() {
        // Given
        WorkbenchStageRunPreparationPlan plan = preparationPlan();
        ResolvedCapabilityResolution capabilities = capabilities(
                plan.getStageSnapshot());
        WorkbenchContextManifest contextManifest = contextManifest();
        WorkbenchStageConversationHistory history =
                WorkbenchStageConversationHistory.freeze(
                        "stage-session-1",
                        "workbench-1:stage-design", 0,
                        "user: previous question\n\nassistant: previous answer",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX);

        // When
        PreparedWorkbenchStagePrompt prompt =
                WorkbenchStageRunPromptComposer.compose(
                        plan, capabilities, null, contextManifest,
                        developmentContext(), workspaceSnapshot(), history,
                        VerifiedWorkbenchStageRunAttachmentSet.of(
                                Collections.emptyList(),
                                Collections.singletonList(
                                        verifiedStageUpload())),
                        "Please finish the design.");

        // Then
        assertEquals(Arrays.asList(
                        WorkbenchPromptPartType.PLATFORM_SAFETY,
                        WorkbenchPromptPartType.ENVIRONMENT_GUARDRAIL,
                        WorkbenchPromptPartType.REPOSITORY_SCOPE,
                        WorkbenchPromptPartType.STAGE_DEFINITION,
                        WorkbenchPromptPartType.STAGE_RULES,
                        WorkbenchPromptPartType.SELECTED_CAPABILITIES,
                        WorkbenchPromptPartType.GLOBAL_CONTEXT,
                        WorkbenchPromptPartType.WORKSPACE_CONTEXT,
                        WorkbenchPromptPartType.ORIGINAL_GOAL,
                        WorkbenchPromptPartType.ATTACHMENTS,
                        WorkbenchPromptPartType.STAGE_HISTORY,
                        WorkbenchPromptPartType.USER_INPUT,
                        WorkbenchPromptPartType.OUTPUT_INSTRUCTION),
                prompt.getParts().stream()
                        .map(WorkbenchPromptPart::getType).toList());
        assertEquals("Design exact aggregate boundaries.",
                part(prompt, WorkbenchPromptPartType.STAGE_RULES).getContent());
        assertTrue(part(prompt, WorkbenchPromptPartType.STAGE_DEFINITION)
                .getContent().contains("方案设计"));
        assertTrue(part(prompt, WorkbenchPromptPartType.STAGE_DEFINITION)
                .getContent().contains(
                        "Handoff directory: ~/.workbench/handoff/solution-design/"));
        assertEquals(contextManifest.getPromptContent(),
                part(prompt, WorkbenchPromptPartType.GLOBAL_CONTEXT)
                        .getContent());
        assertEquals(history.getContent(),
                part(prompt, WorkbenchPromptPartType.STAGE_HISTORY)
                        .getContent());
        assertTrue(part(prompt, WorkbenchPromptPartType.ATTACHMENTS)
                .getContent().contains(
                        "$AGENT_WORKBENCH_ATTACHMENT_DIR/attachment-"));
        assertFalse(part(prompt, WorkbenchPromptPartType.ATTACHMENTS)
                .getContent().contains("phase="));
    }

    @Test
    void should_RejectHistoryOrContextFromAnotherStageRunBoundary() {
        // Given
        WorkbenchStageRunPreparationPlan plan = preparationPlan();
        WorkbenchStageConversationHistory foreignHistory =
                WorkbenchStageConversationHistory.freeze(
                        "another-session", "workbench-1:stage-design", 0,
                        "foreign history",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX);

        // When / Then
        assertThrows(WorkbenchDomainException.class,
                () -> WorkbenchStageRunPromptComposer.compose(
                        plan, capabilities(plan.getStageSnapshot()), null,
                        contextManifest(), developmentContext(),
                        workspaceSnapshot(), foreignHistory,
                        VerifiedWorkbenchStageRunAttachmentSet.of(
                                Collections.emptyList(),
                                Collections.emptyList()),
                        "message"));
        assertThrows(WorkbenchDomainException.class,
                () -> WorkbenchStageRunPromptComposer.compose(
                        plan, capabilities(plan.getStageSnapshot()), null,
                        WorkbenchContextManifest.freeze(
                                WorkbenchId.of("another-workbench"), 0L,
                                WorkbenchDomainFixtures.repeat('d'),
                                Collections.emptyList(), "No documents."),
                        developmentContext(), workspaceSnapshot(),
                        currentHistory(),
                        VerifiedWorkbenchStageRunAttachmentSet.of(
                                Collections.emptyList(),
                                Collections.emptyList()),
                        "message"));
    }

    private WorkbenchPromptPart part(
            PreparedWorkbenchStagePrompt prompt,
            WorkbenchPromptPartType type) {
        return prompt.getParts().stream()
                .filter(candidate -> candidate.getType() == type)
                .findFirst().orElseThrow();
    }

    private WorkbenchStageRunPreparationPlan preparationPlan() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft("solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "形成可实施方案",
                        "Design exact aggregate boundaries.",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()), administrator, NOW);
        WorkbenchStageSnapshot stageSnapshot =
                WorkbenchStageSnapshot.fromPublishedRevision(
                        catalog.publishDraft(
                                "solution-design", catalog.getCatalogVersion(),
                                1L, new ResolvedStageCapabilities(
                                        Collections.emptyList(),
                                        Collections.emptyList(),
                                        Collections.emptyList()),
                                administrator, NOW.plusSeconds(1)));
        Workbench workbench = Workbench.create(
                WorkbenchId.of("workbench-1"), OWNER,
                "Dynamic Workbench", "Build the complete design.",
                AgentType.CODEX, "local",
                WorkbenchDomainFixtures.repositoryScope(),
                WorkbenchDomainFixtures.snapshotReference(
                        "creation-snapshot",
                        WorkbenchDomainFixtures.repeat('a')),
                Collections.singletonList(WorkbenchStageState.initial(
                        "stage-design", stageSnapshot)), NOW);
        workbench.bindStageConversation(
                "stage-design", "stage-session-1", OWNER,
                0L, NOW.plusSeconds(2));
        return workbench.planStageRunPreparation(
                "stage-design", RunMode.DISCUSS_READ_ONLY, OWNER, 1L);
    }

    private ResolvedCapabilityResolution capabilities(
            WorkbenchStageSnapshot stageSnapshot) {
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
                        "m0-2026-07-22");
        return ResolvedCapabilityResolution.of(
                binding, Collections.singletonList(
                        ResolvedCapabilityRuleContent.bind(
                                rule, stageSnapshot.getStageRules())));
    }

    private WorkbenchContextManifest contextManifest() {
        WorkbenchContextDocumentSnapshot document =
                new WorkbenchContextDocumentSnapshot(
                        "context-document-1", "stage-design", null,
                        "方案文档", "领域边界和接口方案",
                        DocumentReference.of(
                                "agent-web", "docs/design.md"),
                        WorkbenchDomainFixtures.repeat('c'),
                        WorkbenchContextDocumentContentState.CURRENT);
        return WorkbenchContextManifest.freeze(
                WorkbenchId.of("workbench-1"), 3L,
                WorkbenchDomainFixtures.repeat('b'),
                Collections.singletonList(document),
                "Context version: 3\n- 方案文档 | agent-web/docs/design.md");
    }

    private WorkbenchStageConversationHistory currentHistory() {
        return WorkbenchStageConversationHistory.freeze(
                "stage-session-1", "workbench-1:stage-design", 0,
                "current history",
                WorkbenchPromptHistoryDelivery.PROMPT_PREFIX);
    }

    private VerifiedWorkbenchStageUploadedConversationAttachment
            verifiedStageUpload() {
        return VerifiedWorkbenchStageUploadedConversationAttachment.restore(
                "stage-upload-1",
                new WorkbenchStageUploadedAttachmentBinding(
                        OWNER, WorkbenchId.of("workbench-1"),
                        "stage-design", "stage-session-1", 0),
                "design.md", "text/markdown", 64L,
                WorkbenchDomainFixtures.repeat('8'),
                WorkbenchDomainFixtures.repeat('9'),
                "attachment-design.md", NOW.plusSeconds(3600), 0L);
    }

    private WorkspaceDevelopmentContext developmentContext() {
        RepositoryDevelopmentContextClassifier classifier =
                new RepositoryDevelopmentContextClassifier();
        return WorkspaceDevelopmentContext.create(
                WorkbenchDomainFixtures.repositoryScope().getScopeHash(),
                "agent-web", Arrays.asList(
                        classifier.classify(
                                "agent-web",
                                EnumSet.of(RepositoryDevelopmentMarker.POM_XML)),
                        classifier.classify(
                                "shared-library", Collections.emptySet())));
    }

    private WorkspaceSnapshot workspaceSnapshot() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Arrays.asList("agent-web", "shared-library"));
        WorkspaceTopology topology = WorkspaceTopology.of(
                "/workspace", selection);
        return WorkspaceSnapshot.capture(
                "run-snapshot", SnapshotPurpose.of("WORKBENCH_RUN_START"),
                topology, Arrays.asList(
                        RepositoryBaseline.capture(
                                "agent-web", "/workspace/agent-web", "main",
                                String.join("", Collections.nCopies(40, "1")),
                                true, WorkbenchDomainFixtures.repeat('1'), NOW),
                        RepositoryBaseline.capture(
                                "shared-library", "/workspace/shared-library",
                                "main",
                                String.join("", Collections.nCopies(40, "2")),
                                true, WorkbenchDomainFixtures.repeat('2'), NOW)),
                Collections.emptyList(), NOW, NOW.plusMillis(1));
    }
}
