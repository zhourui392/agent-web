package com.example.agentweb.infra.knowledge;

import com.example.agentweb.app.knowledge.KnowledgeSuggestionView;
import com.example.agentweb.domain.knowledge.KnowledgeScope;
import com.example.agentweb.domain.knowledge.KnowledgeSuggestion;
import com.example.agentweb.domain.knowledge.SuggestionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * knowledge_suggestion 表读写轻集成（真实 SQLite，不起 Spring）：
 * 全字段往返、审批态再水化不过聚合守卫、状态查询与收割幂等闸。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class SqliteKnowledgeSuggestionRepoTest {

    @TempDir
    Path tempDir;

    private SqliteKnowledgeSuggestionRepo repo;

    @BeforeEach
    public void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE knowledge_suggestion (id TEXT PRIMARY KEY, requirement_id TEXT NOT NULL, "
                + "scope TEXT NOT NULL, source_ref TEXT, title TEXT NOT NULL, trigger_signals_json TEXT, "
                + "phenomenon TEXT, root_cause TEXT, solution TEXT, notes TEXT, status TEXT NOT NULL, "
                + "reject_reason TEXT, reviewed_by TEXT, reviewed_at INTEGER, issue_id TEXT, "
                + "issue_path TEXT, created_at INTEGER NOT NULL)");
        repo = new SqliteKnowledgeSuggestionRepo(jdbc);
    }

    @Test
    public void save_and_find_should_round_trip_pending_suggestion() {
        KnowledgeSuggestion suggestion = KnowledgeSuggestion.create("R2607040001", KnowledgeScope.REPO,
                "MR !12", "下单超时修复知识", "下单接口偶发超时", "连接池耗尽", "调大池并加熔断");
        suggestion.reviseDraft(suggestion.getTitle(), List.of("ERR_TIMEOUT", "下单超时"),
                suggestion.getPhenomenon(), suggestion.getRootCause(), suggestion.getSolution(), "备注");
        repo.save(suggestion);

        KnowledgeSuggestion loaded = repo.findById(suggestion.getId());

        assertNotNull(loaded);
        assertEquals("R2607040001", loaded.getRequirementId());
        assertEquals(KnowledgeScope.REPO, loaded.getScope());
        assertEquals(List.of("ERR_TIMEOUT", "下单超时"), loaded.getTriggerSignals());
        assertEquals(SuggestionStatus.PENDING, loaded.getStatus());
        assertNull(loaded.getReviewedAt());
    }

    @Test
    public void update_should_persist_review_state_and_rehydrate_without_guard() {
        KnowledgeSuggestion suggestion = KnowledgeSuggestion.create("R2607040002", KnowledgeScope.REPO,
                "MR !13", "标题", "现象", "根因", "方案");
        repo.save(suggestion);
        suggestion.approve("V33215020");
        suggestion.recordArchived("I-007", "docs/issue-log/issue/I-007-x.md");
        repo.update(suggestion);

        KnowledgeSuggestion loaded = repo.findById(suggestion.getId());

        assertEquals(SuggestionStatus.APPROVED, loaded.getStatus());
        assertEquals("V33215020", loaded.getReviewedBy());
        assertNotNull(loaded.getReviewedAt());
        assertEquals("I-007", loaded.getIssueId());
        assertEquals("docs/issue-log/issue/I-007-x.md", loaded.getIssuePath());
    }

    @Test
    public void listByStatus_should_project_views_and_exists_should_gate_harvest_idempotency() {
        KnowledgeSuggestion pending = KnowledgeSuggestion.create("R2607040003", KnowledgeScope.REPO,
                "", "待审", "p", "r", "s");
        KnowledgeSuggestion rejected = KnowledgeSuggestion.create("R2607040004", KnowledgeScope.REPO,
                "", "已拒", "p", "r", "s");
        rejected.reject("V33215020", "重复");
        repo.save(pending);
        repo.save(rejected);

        List<KnowledgeSuggestionView> pendings = repo.listByStatus("PENDING", 10);

        assertEquals(1, pendings.size());
        assertEquals("待审", pendings.get(0).title());
        assertTrue(repo.existsForRequirement("R2607040003"));
        assertFalse(repo.existsForRequirement("R9999999999"));
    }
}
