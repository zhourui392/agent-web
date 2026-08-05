package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedCommandBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.workbench.context.WorkbenchContextManifest;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workspace.RepositoryBaseline;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic Stage Run 固定顺序 Prompt 的领域装配器。
 *
 * @author alex
 * @since 2026-08-05
 */
public final class WorkbenchStageRunPromptComposer {

    private static final String RUN_START_PURPOSE = "WORKBENCH_RUN_START";

    private WorkbenchStageRunPromptComposer() {
    }

    public static PreparedWorkbenchStagePrompt compose(
            WorkbenchStageRunPreparationPlan plan,
            ResolvedCapabilityResolution capabilityResolution,
            ResolvedCommandBinding commandBinding,
            WorkbenchContextManifest contextManifest,
            WorkspaceDevelopmentContext developmentContext,
            WorkspaceSnapshot workspaceSnapshot,
            WorkbenchStageConversationHistory history,
            VerifiedWorkbenchStageRunAttachmentSet verifiedAttachments,
            String userInput) {
        requireFacts(plan, capabilityResolution, contextManifest,
                developmentContext, workspaceSnapshot, history,
                verifiedAttachments);
        requireBindings(plan, capabilityResolution, contextManifest,
                developmentContext, workspaceSnapshot, history);
        List<WorkbenchPromptPart> parts = requiredParts(
                plan, capabilityResolution, commandBinding,
                contextManifest, developmentContext, workspaceSnapshot);
        appendOptionalParts(parts, history, verifiedAttachments);
        parts.add(part(WorkbenchPromptPartType.USER_INPUT,
                "owner/current-message", userInput));
        parts.add(part(WorkbenchPromptPartType.OUTPUT_INSTRUCTION,
                "platform/workbench-stage-output@1",
                "围绕当前 Stage 目标给出可验证结果；明确事实、假设、修改、测试证据与残余风险；"
                        + "不得把工具原始输出或敏感路径当作最终答复。"));
        return PreparedWorkbenchStagePrompt.assemble(
                parts, history.getDelivery());
    }

    private static void requireFacts(
            WorkbenchStageRunPreparationPlan plan,
            ResolvedCapabilityResolution capabilityResolution,
            WorkbenchContextManifest contextManifest,
            WorkspaceDevelopmentContext developmentContext,
            WorkspaceSnapshot workspaceSnapshot,
            WorkbenchStageConversationHistory history,
            VerifiedWorkbenchStageRunAttachmentSet verifiedAttachments) {
        if (plan == null || capabilityResolution == null
                || contextManifest == null || developmentContext == null
                || workspaceSnapshot == null || history == null
                || verifiedAttachments == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage Prompt preparation facts are required");
        }
    }

    private static void requireBindings(
            WorkbenchStageRunPreparationPlan plan,
            ResolvedCapabilityResolution capabilityResolution,
            WorkbenchContextManifest contextManifest,
            WorkspaceDevelopmentContext developmentContext,
            WorkspaceSnapshot workspaceSnapshot,
            WorkbenchStageConversationHistory history) {
        WorkbenchStageSnapshot stage = plan.getStageSnapshot();
        ResolvedCapabilityBinding binding = capabilityResolution.getBinding();
        if (!stage.getSnapshotHash().equals(binding.getProfileHash())
                || !RUN_START_PURPOSE.equals(
                workspaceSnapshot.getPurpose().getValue())
                || !plan.getRepositoryScope().matchesSnapshotTopology(
                workspaceSnapshot.reference())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        history.requireCurrent(plan.getConversation());
        contextManifest.requireCurrent(plan);
        plan.requireDevelopmentContext(developmentContext);
    }

    private static List<WorkbenchPromptPart> requiredParts(
            WorkbenchStageRunPreparationPlan plan,
            ResolvedCapabilityResolution capabilityResolution,
            ResolvedCommandBinding commandBinding,
            WorkbenchContextManifest contextManifest,
            WorkspaceDevelopmentContext developmentContext,
            WorkspaceSnapshot workspaceSnapshot) {
        List<WorkbenchPromptPart> parts =
                new ArrayList<WorkbenchPromptPart>();
        parts.add(part(WorkbenchPromptPartType.PLATFORM_SAFETY,
                "platform/workbench-safety@1",
                "遵守平台权限和安全边界；不得读取、输出或持久化凭据与 Secret；"
                        + "未明确授权的路径、命令和外部能力默认拒绝。"));
        parts.add(part(WorkbenchPromptPartType.ENVIRONMENT_GUARDRAIL,
                "workbench/environment", environmentGuardrail(plan)));
        parts.add(part(WorkbenchPromptPartType.REPOSITORY_SCOPE,
                "workbench/repository-scope@1", repositoryScope(plan)));
        parts.add(part(WorkbenchPromptPartType.STAGE_DEFINITION,
                "workbench/stage/" + plan.getStageInstanceIdentifier(),
                stageDefinition(plan)));
        parts.add(part(WorkbenchPromptPartType.STAGE_RULES,
                "workbench/stage-rules/"
                        + plan.getStageSnapshot().getSnapshotHash(),
                plan.getStageSnapshot().getStageRules()));
        parts.add(part(WorkbenchPromptPartType.SELECTED_CAPABILITIES,
                "capability-binding/"
                        + capabilityResolution.getBinding().getBindingHash(),
                capabilities(capabilityResolution, commandBinding)));
        parts.add(part(WorkbenchPromptPartType.GLOBAL_CONTEXT,
                "workbench/context/" + contextManifest.getContextVersion()
                        + "/" + contextManifest.getContextHash(),
                contextManifest.getPromptContent()));
        parts.add(part(WorkbenchPromptPartType.WORKSPACE_CONTEXT,
                "workspace-snapshot/" + workspaceSnapshot.getSnapshotId()
                        + "/development-context/"
                        + developmentContext.getContextHash(),
                workspaceContext(workspaceSnapshot, developmentContext)));
        parts.add(part(WorkbenchPromptPartType.ORIGINAL_GOAL,
                "workbench/original-goal@1", plan.getOriginalGoal()));
        return parts;
    }

    private static void appendOptionalParts(
            List<WorkbenchPromptPart> parts,
            WorkbenchStageConversationHistory history,
            VerifiedWorkbenchStageRunAttachmentSet verifiedAttachments) {
        if (!verifiedAttachments.isEmpty()) {
            parts.add(part(WorkbenchPromptPartType.ATTACHMENTS,
                    "workbench/stage-attachments@1",
                    attachments(verifiedAttachments)));
        }
        if (history.hasContent()) {
            parts.add(part(WorkbenchPromptPartType.STAGE_HISTORY,
                    "stage-session/" + history.getSessionId()
                            + "/generation/"
                            + history.getConversationGeneration(),
                    history.getContent()));
        }
    }

    private static WorkbenchPromptPart part(
            WorkbenchPromptPartType type, String source, String content) {
        return WorkbenchPromptPart.of(type, source, content);
    }

    private static String environmentGuardrail(
            WorkbenchStageRunPreparationPlan plan) {
        String environment = plan.getEnvironment() == null
                ? "UNSPECIFIED" : plan.getEnvironment();
        return "Environment: " + environment
                + "\n只在该运行声明的环境与已选 Repository Scope 内操作；"
                + "环境信息不构成额外文件或外部系统授权。";
    }

    private static String repositoryScope(
            WorkbenchStageRunPreparationPlan plan) {
        StringBuilder result = new StringBuilder();
        result.append("RunMode: ").append(plan.getRunMode().name())
                .append("\nPrimary repository: ")
                .append(plan.getRepositoryScope().getPrimaryRepositoryKey())
                .append("\nRepository scope hash: ")
                .append(plan.getRepositoryScope().getScopeHash());
        for (ResolvedRepository repository
                : plan.getRepositoryScope().getRepositories()) {
            result.append("\n- ").append(repository.getRepositoryKey())
                    .append(" => ").append(repository.getRepositoryRoot());
        }
        return result.toString();
    }

    private static String stageDefinition(
            WorkbenchStageRunPreparationPlan plan) {
        WorkbenchStageSnapshot stage = plan.getStageSnapshot();
        return "Stage instance: " + plan.getStageInstanceIdentifier()
                + "\nDefinition: " + stage.getDefinitionIdentifier()
                + "@" + stage.getDefinitionRevision()
                + "\nSequence: " + stage.getSequenceNumber()
                + "\nName: " + stage.getDisplayName()
                + "\nDescription: " + stage.getDescription()
                + "\nSnapshot hash: " + stage.getSnapshotHash()
                + "\nRunMode: " + plan.getRunMode().name();
    }

    private static String capabilities(
            ResolvedCapabilityResolution resolution,
            ResolvedCommandBinding commandBinding) {
        ResolvedCapabilityBinding binding = resolution.getBinding();
        StringBuilder result = new StringBuilder();
        result.append("Binding: ").append(binding.getBindingHash())
                .append("\nProfile: ").append(binding.getProfileId())
                .append('@').append(binding.getProfileVersion())
                .append("\nRuntime compatibility: ")
                .append(binding.getRuntimeCompatibility());
        if (commandBinding != null) {
            result.append("\n- COMMAND ")
                    .append(commandBinding.getIdentifier()).append('@')
                    .append(commandBinding.getVersion())
                    .append(" contentHash=")
                    .append(commandBinding.getContentHash())
                    .append(" expandedPromptHash=")
                    .append(commandBinding.getExpandedPromptHash());
        }
        appendCapabilityReferences(result, binding);
        return result.toString();
    }

    private static void appendCapabilityReferences(
            StringBuilder result, ResolvedCapabilityBinding binding) {
        for (ResolvedRuleBinding rule : binding.getRules()) {
            result.append("\n- RULE ").append(rule.getId()).append('@')
                    .append(rule.getVersion()).append(" contentHash=")
                    .append(rule.getContentHash());
        }
        for (ResolvedSkillBinding skill : binding.getSkills()) {
            result.append("\n- SKILL ").append(skill.getId()).append('@')
                    .append(skill.getVersion());
        }
        for (ResolvedMcpServerBinding mcp : binding.getMcpServers()) {
            result.append("\n- MCP ").append(mcp.getId()).append('@')
                    .append(mcp.getVersion()).append(" access=")
                    .append(mcp.getAccess());
        }
        for (RejectedCapability rejected : binding.getRejected()) {
            result.append("\n- REJECTED ").append(rejected.getId())
                    .append(" reason=").append(rejected.getReasonCode());
        }
    }

    private static String workspaceContext(
            WorkspaceSnapshot snapshot,
            WorkspaceDevelopmentContext developmentContext) {
        StringBuilder result = new StringBuilder();
        result.append("Snapshot: ").append(snapshot.getSnapshotId())
                .append("\nPurpose: ")
                .append(snapshot.getPurpose().getValue())
                .append("\nState hash: ").append(snapshot.getStateHash())
                .append("\nClean: ").append(snapshot.isClean());
        for (RepositoryBaseline repository : snapshot.getRepositories()) {
            result.append("\n- ").append(repository.getRepositoryKey())
                    .append(" branch=").append(repository.getBranch())
                    .append(" head=").append(repository.getHead())
                    .append(" clean=").append(repository.isClean())
                    .append(" diffHash=").append(repository.getDiffHash());
        }
        result.append("\nDevelopment context hash: ")
                .append(developmentContext.getContextHash())
                .append("\nPrimary development repository: ")
                .append(developmentContext.getPrimaryRepositoryKey());
        for (RepositoryDevelopmentContext repository
                : developmentContext.getRepositories()) {
            result.append("\n- ").append(repository.getRepositoryKey())
                    .append(" technologies=")
                    .append(enumNames(repository.getTechnologyTypes()))
                    .append(" buildTools=")
                    .append(enumNames(repository.getBuildTools()));
            for (RepositoryInstructionReference instruction
                    : repository.getInstructionReferences()) {
                result.append("\n  instruction=")
                        .append(instruction.getType().name()).append(':')
                        .append(instruction.getRelativePath());
            }
        }
        return result.toString();
    }

    private static String enumNames(List<? extends Enum<?>> values) {
        if (values.isEmpty()) {
            return "NONE";
        }
        StringBuilder result = new StringBuilder();
        for (Enum<?> value : values) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(value.name());
        }
        return result.toString();
    }

    private static String attachments(
            VerifiedWorkbenchStageRunAttachmentSet verifiedAttachments) {
        StringBuilder result = new StringBuilder();
        for (VerifiedWorkbenchRunAttachment attachment
                : verifiedAttachments.getRepositoryDocuments()) {
            DocumentReference reference = attachment.getDocumentReference();
            appendLine(result, "- repositoryKey="
                    + reference.getRepositoryKey() + " relativePath="
                    + reference.getRelativePath() + " contentHash="
                    + attachment.getContentVersion() + " mediaType="
                    + attachment.getMediaType() + " size="
                    + attachment.getSize());
        }
        for (VerifiedWorkbenchStageUploadedConversationAttachment attachment
                : verifiedAttachments.getUploadedAttachments()) {
            appendLine(result, "- type=UPLOADED_CONVERSATION attachmentId="
                    + attachment.getAttachmentId() + " displayName="
                    + attachment.getDisplayName() + " runtimeReference="
                    + attachment.runtimeReference() + " contentHash="
                    + attachment.getContentHash() + " mediaType="
                    + attachment.getMediaType() + " size="
                    + attachment.getSize());
        }
        return result.toString();
    }

    private static void appendLine(StringBuilder target, String line) {
        if (target.length() > 0) {
            target.append('\n');
        }
        target.append(line);
    }
}
