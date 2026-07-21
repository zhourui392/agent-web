package com.example.agentweb.app.knowledge;

import com.example.agentweb.domain.issuelog.IssueLogDraft;
import com.example.agentweb.domain.issuelog.IssueLogEntry;
import com.example.agentweb.domain.issuelog.IssueLogRepository;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestion;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestionRepository;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 收件箱审批编排（M4）：approve = 归档校验 → 聚合审批 → 经既有 issue-log 通道写入需求
 * worktree 的 docs/issue-log（REPO 作用域，用户 review 后随 MR 走 git）→ 回填 issueId。
 * 落盘失败不持久化审批（DB 仍 PENDING 可重试）；不自动 git commit（issue-log 通道现约定）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Slf4j
public class KnowledgeInboxAppService {

    private static final String CATEGORY_REQUIREMENT_DELIVERY = "requirement-delivery";

    private final KnowledgeSuggestionRepository suggestionRepository;
    private final WorkspaceRepository workspaceRepository;
    private final IssueLogRepository issueLogRepository;

    public KnowledgeInboxAppService(KnowledgeSuggestionRepository suggestionRepository,
                                    WorkspaceRepository workspaceRepository,
                                    IssueLogRepository issueLogRepository) {
        this.suggestionRepository = suggestionRepository;
        this.workspaceRepository = workspaceRepository;
        this.issueLogRepository = issueLogRepository;
    }

    /**
     * 审批通过并经 issue-log 通道落盘。
     *
     * @param suggestionId 候选 ID
     * @param reviewer     审批人
     * @return 已回填 issueId/issuePath 的聚合
     */
    public KnowledgeSuggestion approve(String suggestionId, String reviewer) {
        KnowledgeSuggestion suggestion = load(suggestionId);
        RequirementWorkspace workspace = requiredWorkspace(suggestion.getRequirementId());
        Path worktree = Path.of(workspace.getWorktreePath());
        IssueLogDraft draft = toDraft(suggestion, workspace);
        draft.requireArchivable();

        suggestion.approve(reviewer);
        IssueLogEntry entry = issueLogRepository.save(worktree, draft);
        suggestion.recordArchived(entry.getId(), entry.getFilePath());
        suggestionRepository.update(suggestion);
        log.info("knowledge-suggestion-approved id={} issueId={}", suggestionId, entry.getId());
        return suggestion;
    }

    public void reject(String suggestionId, String reviewer, String reason) {
        KnowledgeSuggestion suggestion = load(suggestionId);
        suggestion.reject(reviewer, reason);
        suggestionRepository.update(suggestion);
    }

    /** 审批前编辑草稿（补触发词等）。 */
    public void revise(String suggestionId, String title, List<String> triggerSignals,
                       String phenomenon, String rootCause, String solution, String notes) {
        KnowledgeSuggestion suggestion = load(suggestionId);
        suggestion.reviseDraft(title, triggerSignals, phenomenon, rootCause, solution, notes);
        suggestionRepository.update(suggestion);
    }

    private KnowledgeSuggestion load(String suggestionId) {
        KnowledgeSuggestion suggestion = suggestionRepository.findById(suggestionId);
        if (suggestion == null) {
            throw new NoSuchElementException("knowledge suggestion not found: " + suggestionId);
        }
        return suggestion;
    }

    /** REPO 作用域落目标仓 worktree；工作区记录缺失或磁盘目录已清理都拒绝（提示先重挂工作区）。 */
    private RequirementWorkspace requiredWorkspace(String requirementId) {
        RequirementWorkspace workspace = workspaceRepository.findByRequirementId(requirementId);
        if (workspace == null) {
            throw new IllegalStateException("approve rejected: workspace missing for " + requirementId
                    + ", 请先重新挂载需求工作区");
        }
        if (!Files.isDirectory(Path.of(workspace.getWorktreePath()))) {
            throw new IllegalStateException("approve rejected: worktree not on disk "
                    + workspace.getWorktreePath() + ", 请先重新挂载需求工作区");
        }
        return workspace;
    }

    private IssueLogDraft toDraft(KnowledgeSuggestion suggestion, RequirementWorkspace workspace) {
        String notes = suggestion.getSourceRef().isBlank()
                ? suggestion.getNotes()
                : appendSourceLine(suggestion.getNotes(), suggestion.getSourceRef());
        return new IssueLogDraft(
                suggestion.getTitle(),
                List.of(CATEGORY_REQUIREMENT_DELIVERY),
                List.of(repoSlugOf(workspace.getRepoUrl())),
                suggestion.getTriggerSignals(),
                suggestion.getPhenomenon(),
                suggestion.getRootCause(),
                suggestion.getSolution(),
                notes);
    }

    private String appendSourceLine(String notes, String sourceRef) {
        return notes.isBlank() ? "来源: " + sourceRef : notes + "\n来源: " + sourceRef;
    }

    /** services 列至少要 1 个 token（IssueLogDraft 不变量），取仓库地址末段（去 .git）标识服务。 */
    private String repoSlugOf(String repoUrl) {
        String trimmed = repoUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - 4);
        }
        int lastSlash = trimmed.lastIndexOf('/');
        String slug = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
        return slug.isBlank() ? "unknown-service" : slug;
    }
}
