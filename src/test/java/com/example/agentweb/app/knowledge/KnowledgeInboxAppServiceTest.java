package com.example.agentweb.app.knowledge;

import com.example.agentweb.domain.issuelog.IssueLogDraft;
import com.example.agentweb.domain.issuelog.IssueLogEntry;
import com.example.agentweb.domain.issuelog.IssueLogRepository;
import com.example.agentweb.domain.knowledge.KnowledgeScope;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestion;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestionRepository;
import com.example.agentweb.domain.knowledge.SuggestionStatus;
import com.example.agentweb.domain.workspace.RequirementWorkspace;
import com.example.agentweb.domain.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收件箱审批编排：approve 经 issue-log 通道写需求 worktree 并回填 issueId；
 * 工作区缺席/触发词空都在审批持久化前拒绝（DB 保持 PENDING 可重试）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class KnowledgeInboxAppServiceTest {

    private static final String REQ_ID = "R2607040001";
    private static final String REVIEWER = "V33215020";

    @TempDir
    Path worktreeDir;

    private KnowledgeSuggestionRepository suggestionRepository;
    private WorkspaceRepository workspaceRepository;
    private IssueLogRepository issueLogRepository;
    private KnowledgeInboxAppService service;

    @BeforeEach
    public void setUp() {
        suggestionRepository = mock(KnowledgeSuggestionRepository.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        issueLogRepository = mock(IssueLogRepository.class);
        service = new KnowledgeInboxAppService(suggestionRepository, workspaceRepository,
                issueLogRepository);
    }

    @Test
    public void approve_should_archive_via_issue_log_channel_and_record_result() {
        KnowledgeSuggestion suggestion = suggestionWithSignals();
        when(suggestionRepository.findById(suggestion.getId())).thenReturn(suggestion);
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(readyWorkspace());
        IssueLogEntry entry = new IssueLogEntry("I-007", draftStub(),
                "docs/issue-log/issue/I-007-x.md", Instant.now());
        when(issueLogRepository.save(eq(worktreeDir), any())).thenReturn(entry);

        KnowledgeSuggestion approved = service.approve(suggestion.getId(), REVIEWER);

        ArgumentCaptor<IssueLogDraft> draftCaptor = ArgumentCaptor.forClass(IssueLogDraft.class);
        verify(issueLogRepository).save(eq(worktreeDir), draftCaptor.capture());
        IssueLogDraft draft = draftCaptor.getValue();
        assertEquals(List.of("requirement-delivery"), draft.getCategories());
        assertEquals(List.of("market-service"), draft.getServices());
        assertTrue(draft.getNotes().contains("MR !12"));

        verify(suggestionRepository).update(suggestion);
        assertEquals(SuggestionStatus.APPROVED, approved.getStatus());
        assertEquals("I-007", approved.getIssueId());
    }

    @Test
    public void approve_should_fail_before_persist_when_workspace_missing() {
        KnowledgeSuggestion suggestion = suggestionWithSignals();
        when(suggestionRepository.findById(suggestion.getId())).thenReturn(suggestion);
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.approve(suggestion.getId(), REVIEWER));

        verify(issueLogRepository, never()).save(any(), any());
        verify(suggestionRepository, never()).update(any());
    }

    @Test
    public void approve_should_fail_when_worktree_cleaned_from_disk() {
        KnowledgeSuggestion suggestion = suggestionWithSignals();
        when(suggestionRepository.findById(suggestion.getId())).thenReturn(suggestion);
        RequirementWorkspace workspace = RequirementWorkspace.create(REQ_ID,
                "https://gitlab.example.com/platform-server/market-service.git",
                "D:/ws/mirrors/market-service.git",
                worktreeDir.resolve("gone").toString(), 72);
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(workspace);

        assertThrows(IllegalStateException.class,
                () -> service.approve(suggestion.getId(), REVIEWER));
        verify(suggestionRepository, never()).update(any());
    }

    @Test
    public void approve_should_require_trigger_signals_and_keep_pending() {
        KnowledgeSuggestion suggestion = KnowledgeSuggestion.create(REQ_ID, KnowledgeScope.REPO,
                "MR !12", "标题", "现象", "根因", "方案");
        when(suggestionRepository.findById(suggestion.getId())).thenReturn(suggestion);
        when(workspaceRepository.findByRequirementId(REQ_ID)).thenReturn(readyWorkspace());

        assertThrows(IllegalArgumentException.class,
                () -> service.approve(suggestion.getId(), REVIEWER));

        assertEquals(SuggestionStatus.PENDING, suggestion.getStatus());
        verify(issueLogRepository, never()).save(any(), any());
        verify(suggestionRepository, never()).update(any());
    }

    @Test
    public void reject_and_revise_should_persist_through_repository() {
        KnowledgeSuggestion toReject = suggestionWithSignals();
        when(suggestionRepository.findById(toReject.getId())).thenReturn(toReject);
        service.reject(toReject.getId(), REVIEWER, "重复知识");
        assertEquals(SuggestionStatus.REJECTED, toReject.getStatus());
        verify(suggestionRepository).update(toReject);

        KnowledgeSuggestion toRevise = suggestionWithSignals();
        when(suggestionRepository.findById(toRevise.getId())).thenReturn(toRevise);
        service.revise(toRevise.getId(), "新标题", List.of("新触发词"), "p", "r", "s", "n");
        assertEquals("新标题", toRevise.getTitle());
        verify(suggestionRepository).update(toRevise);
    }

    @Test
    public void approve_should_throw_when_suggestion_not_found() {
        when(suggestionRepository.findById("KS-missing")).thenReturn(null);

        assertThrows(NoSuchElementException.class, () -> service.approve("KS-missing", REVIEWER));
    }

    private KnowledgeSuggestion suggestionWithSignals() {
        KnowledgeSuggestion suggestion = KnowledgeSuggestion.create(REQ_ID, KnowledgeScope.REPO,
                "MR !12", "下单超时修复知识", "下单接口偶发超时", "连接池耗尽", "调大池并加熔断");
        suggestion.reviseDraft(suggestion.getTitle(), List.of("ERR_TIMEOUT"),
                suggestion.getPhenomenon(), suggestion.getRootCause(), suggestion.getSolution(), "");
        return suggestion;
    }

    private RequirementWorkspace readyWorkspace() {
        return RequirementWorkspace.create(REQ_ID,
                "https://gitlab.example.com/platform-server/market-service.git",
                "D:/ws/mirrors/market-service.git", worktreeDir.toString(), 72);
    }

    private IssueLogDraft draftStub() {
        return new IssueLogDraft("t", List.of("c"), List.of("s"), List.of("k"), "", "", "", "");
    }
}
