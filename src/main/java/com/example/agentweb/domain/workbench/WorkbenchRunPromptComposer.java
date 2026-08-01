package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.workspace.RepositoryBaseline;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Workbench Run 固定顺序 Prompt 的领域装配器。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchRunPromptComposer {

    private WorkbenchRunPromptComposer() {
    }

    public static PreparedWorkbenchPrompt compose(
            WorkbenchRunPreparationPlan plan,
            ResolvedCapabilityResolution capabilityResolution,
            PhaseHandoffRevision handoffRevision,
            WorkspaceSnapshot workspaceSnapshot,
            WorkbenchPhaseHistory history,
            String userInput) {
        if (plan == null || capabilityResolution == null
                || workspaceSnapshot == null || history == null) {
            throw new IllegalArgumentException(
                    "workbench prompt preparation facts must be complete");
        }
        ResolvedCapabilityBinding capabilityBinding =
                capabilityResolution.getBinding();
        history.requireCurrent(plan.getConversation());
        List<WorkbenchPromptPart> parts =
                new ArrayList<WorkbenchPromptPart>();
        parts.add(part(
                WorkbenchPromptPartType.PLATFORM_SAFETY,
                "platform/workbench-safety@1",
                "遵守平台权限和安全边界；不得读取、输出或持久化凭据与 Secret；"
                        + "未明确授权的路径、命令和外部能力默认拒绝。"));
        parts.add(part(
                WorkbenchPromptPartType.ENVIRONMENT_GUARDRAIL,
                "workbench/environment",
                environmentGuardrail(plan)));
        parts.add(part(
                WorkbenchPromptPartType.REPOSITORY_SCOPE,
                "workbench/repository-scope@1",
                repositoryScope(plan)));
        parts.add(part(
                WorkbenchPromptPartType.PHASE_RULES,
                "workbench/phase-rules@1",
                phaseRules(plan.getPhase(), plan.getRunMode())));
        parts.add(part(
                WorkbenchPromptPartType.SELECTED_CAPABILITIES,
                "capability-binding/" + capabilityBinding.getBindingHash(),
                capabilities(capabilityResolution)));
        if (plan.requiresHandoff()) {
            parts.add(part(
                    WorkbenchPromptPartType.UPSTREAM_HANDOFF,
                    "workbench/handoff/"
                            + handoffRevision.getSourcePhase().name()
                            + "/" + handoffRevision.getVersion(),
                    handoff(handoffRevision)));
        } else if (handoffRevision != null) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        parts.add(part(
                WorkbenchPromptPartType.WORKSPACE_CONTEXT,
                "workspace-snapshot/" + workspaceSnapshot.getSnapshotId(),
                workspaceContext(workspaceSnapshot)));
        if (history.hasContent()) {
            parts.add(part(
                    WorkbenchPromptPartType.PHASE_HISTORY,
                    "phase-session/" + history.getSessionId(),
                    history.getContent()));
        }
        parts.add(part(
                WorkbenchPromptPartType.USER_INPUT,
                "owner/current-message", userInput));
        parts.add(part(
                WorkbenchPromptPartType.OUTPUT_INSTRUCTION,
                "platform/workbench-output@1",
                "围绕当前 Phase 目标给出可验证结果；明确事实、假设、修改、测试证据与残余风险；"
                        + "不得把工具原始输出或敏感路径当作最终答复。"));
        return PreparedWorkbenchPrompt.assemble(
                parts, history.getDelivery());
    }

    private static WorkbenchPromptPart part(
            WorkbenchPromptPartType type, String source, String content) {
        return WorkbenchPromptPart.of(type, source, content);
    }

    private static String environmentGuardrail(
            WorkbenchRunPreparationPlan plan) {
        String environment = plan.getEnvironment() == null
                ? "UNSPECIFIED" : plan.getEnvironment();
        return "Environment: " + environment
                + "\n只在该运行声明的环境与已选 Repository Scope 内操作；"
                + "环境信息不构成额外文件或外部系统授权。";
    }

    private static String repositoryScope(
            WorkbenchRunPreparationPlan plan) {
        StringBuilder result = new StringBuilder();
        result.append("RunMode: ").append(plan.getRunMode().name())
                .append("\nPrimary repository: ")
                .append(plan.getRepositoryScope().getPrimaryRepositoryKey())
                .append("\nRepository scope hash: ")
                .append(plan.getRepositoryScope().getScopeHash());
        for (ResolvedRepository repository
                : plan.getRepositoryScope().getRepositories()) {
            result.append("\n- ")
                    .append(repository.getRepositoryKey())
                    .append(" => ")
                    .append(repository.getRepositoryRoot());
        }
        return result.toString();
    }

    private static String phaseRules(
            WorkbenchPhase phase, RunMode runMode) {
        String rule;
        switch (phase) {
            case REQUIREMENT_ANALYSIS:
                rule = "核实事实、目标、范围、约束和验收标准；不得修改工作区。";
                break;
            case SOLUTION_DESIGN:
                rule = "输出可落地的领域、接口、数据、风险、回滚与测试设计；不得修改工作区。";
                break;
            case IMPLEMENT_TEST:
                rule = "遵循仓库规范与 TDD，保持最小修改并提供聚焦测试证据。";
                break;
            case REVIEW_REFACTOR:
                rule = "以人工 Review 意见为边界，先核实问题，再执行明确授权的重构与回归。";
                break;
            default:
                throw new IllegalStateException("unsupported workbench phase");
        }
        return "Phase: " + phase.name() + "\nRunMode: "
                + runMode.name() + "\n" + rule;
    }

    private static String capabilities(
            ResolvedCapabilityResolution resolution) {
        ResolvedCapabilityBinding binding = resolution.getBinding();
        StringBuilder result = new StringBuilder();
        result.append("Binding: ").append(binding.getBindingHash())
                .append("\nProfile: ")
                .append(binding.getProfileId()).append('@')
                .append(binding.getProfileVersion())
                .append("\nRuntime compatibility: ")
                .append(binding.getRuntimeCompatibility());
        for (ResolvedCapabilityRuleContent rule
                : resolution.getRuleContents()) {
            result.append("\n- RULE ").append(rule.getId())
                    .append('@').append(rule.getVersion())
                    .append(" contentHash=")
                    .append(rule.getContentHash())
                    .append("\n").append(rule.getContent());
        }
        for (ResolvedSkillBinding skill : binding.getSkills()) {
            result.append("\n- SKILL ").append(skill.getId())
                    .append('@').append(skill.getVersion());
        }
        for (ResolvedMcpServerBinding mcp : binding.getMcpServers()) {
            result.append("\n- MCP ").append(mcp.getId())
                    .append('@').append(mcp.getVersion())
                    .append(" access=").append(mcp.getAccess());
        }
        for (RejectedCapability rejected : binding.getRejected()) {
            result.append("\n- REJECTED ").append(rejected.getId())
                    .append(" reason=").append(rejected.getReasonCode());
        }
        return result.toString();
    }

    private static String handoff(PhaseHandoffRevision revision) {
        StringBuilder result = new StringBuilder();
        result.append("Source phase: ")
                .append(revision.getSourcePhase().name())
                .append("\nVersion: ").append(revision.getVersion())
                .append("\nHash: ").append(revision.getContentHash())
                .append("\nSummary:\n").append(revision.getSummary());
        for (Decision decision : revision.getDecisions()) {
            result.append("\nDecision [")
                    .append(decision.getStatus().name())
                    .append("]: ").append(decision.getText());
            if (decision.getRationale() != null) {
                result.append(" | rationale: ")
                        .append(decision.getRationale());
            }
        }
        for (OpenQuestion question : revision.getOpenQuestions()) {
            result.append("\nOpen question: ")
                    .append(question.getText());
            if (question.getOwnerHint() != null) {
                result.append(" | owner: ")
                        .append(question.getOwnerHint());
            }
        }
        for (DocumentReference file : revision.getPinnedFiles()) {
            result.append("\nPinned file: ")
                    .append(file.getRepositoryKey()).append('/')
                    .append(file.getRelativePath());
        }
        for (WorkbenchRunReference run : revision.getReferencedRuns()) {
            result.append("\nReferenced run: ")
                    .append(run.getRunId()).append(" phase=")
                    .append(run.getPhase().name()).append(" | ")
                    .append(run.getSafeSummary());
        }
        return result.toString();
    }

    private static String workspaceContext(WorkspaceSnapshot snapshot) {
        StringBuilder result = new StringBuilder();
        result.append("Snapshot: ").append(snapshot.getSnapshotId())
                .append("\nPurpose: ")
                .append(snapshot.getPurpose().getValue())
                .append("\nState hash: ")
                .append(snapshot.getStateHash())
                .append("\nClean: ").append(snapshot.isClean());
        for (RepositoryBaseline repository : snapshot.getRepositories()) {
            result.append("\n- ")
                    .append(repository.getRepositoryKey())
                    .append(" branch=").append(repository.getBranch())
                    .append(" head=").append(repository.getHead())
                    .append(" clean=").append(repository.isClean())
                    .append(" diffHash=").append(repository.getDiffHash());
        }
        return result.toString();
    }
}
