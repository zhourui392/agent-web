package com.example.agentweb.infra.metrics;

import com.example.agentweb.app.metrics.ConversationDetail;
import com.example.agentweb.app.metrics.ConversationPage;
import com.example.agentweb.app.metrics.ConversationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话记录查询服务的 Infra 轻量集成测试:真实 SQLite + @TempDir,不起 Spring。
 * 验证全量(不按用户隔离)、分页、关键字过滤、倒序、详情消息流与缺失返回 null。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public class SqliteConversationQueryServiceTest {

    @TempDir
    Path tempDir;

    private SqliteConversationQueryService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("conversations-test.db"));
        jdbc = new JdbcTemplate(ds);
        service = new SqliteConversationQueryService(jdbc);
        createTables();
        seedData();
    }

    @Test
    void list_returnsAllUsersOrderedByCreatedAtDesc() {
        ConversationPage page = service.list(1, 10, null);

        assertEquals(3L, page.getTotal());
        assertEquals(3, page.getRows().size());
        // s3 (created 2026-03-03) 最新在前, s1 (2026-01-01) 最后
        assertEquals("s3", page.getRows().get(0).getSessionId());
        assertEquals("s1", page.getRows().get(2).getSessionId());
    }

    @Test
    void list_projectsUserAndCountsAndFeedback() {
        ConversationRecord top = service.list(1, 10, null).getRows().get(0);

        assertEquals("s3", top.getSessionId());
        assertEquals("CODEX", top.getAgentType());
        assertEquals("E10003", top.getUserId());
        assertEquals("王五", top.getUserName());
        assertEquals(0L, top.getMessageCount());
    }

    @Test
    void list_titleFallsBackToFirstUserMessage() {
        // s1 标题为 null, 应回退到首条 user 消息;按用户名定位该行
        ConversationRecord s1 = service.list(1, 10, "张三").getRows().get(0);
        assertEquals("s1", s1.getSessionId());
        assertEquals("第一条用户问题", s1.getTitle());
        assertEquals(2L, s1.getMessageCount());
        assertEquals("CORRECT", s1.getFeedbackRating());
    }

    @Test
    void list_paginationSlicesByPageAndSize() {
        ConversationPage firstPage = service.list(1, 2, null);
        ConversationPage secondPage = service.list(2, 2, null);

        assertEquals(3L, firstPage.getTotal());
        assertEquals(2, firstPage.getRows().size());
        assertEquals(1, secondPage.getRows().size());
        assertEquals("s1", secondPage.getRows().get(0).getSessionId());
    }

    @Test
    void list_keywordMatchesTitleOrUserNameOrUserId() {
        assertEquals(1L, service.list(1, 10, "李四").getTotal());
        assertEquals(1L, service.list(1, 10, "E10003").getTotal());
        // s2 标题含 "部署"
        assertEquals(1L, service.list(1, 10, "部署").getTotal());
        assertEquals(0L, service.list(1, 10, "不存在的关键字").getTotal());
    }

    @Test
    void list_keywordMatchesFallbackTitleWhenTitleNull() {
        // s1 title 为 null, 列表标题回退到首条 user 消息「第一条用户问题」;
        // 按该回退标题文本搜索应命中 s1(修复前 WHERE 只匹配 s.title 原始列 → 漏)。
        ConversationPage page = service.list(1, 10, "第一条用户问题");
        assertEquals(1L, page.getTotal());
        assertEquals("s1", page.getRows().get(0).getSessionId());
    }

    @Test
    void detail_returnsRecordWithMessagesInOrder() {
        ConversationDetail detail = service.detail("s1");

        assertEquals("s1", detail.getRecord().getSessionId());
        assertEquals(2, detail.getMessages().size());
        assertEquals("user", detail.getMessages().get(0).getRole());
        assertEquals("第一条用户问题", detail.getMessages().get(0).getContent());
        assertEquals("assistant", detail.getMessages().get(1).getRole());
    }

    @Test
    void detail_missingSession_returnsNull() {
        assertNull(service.detail("nope"));
    }

    @Test
    void list_emptyKeyword_treatedAsNoFilter() {
        assertEquals(3L, service.list(1, 10, "   ").getTotal());
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE chat_session ("
                + "id TEXT PRIMARY KEY, agent_type TEXT, working_dir TEXT, created_at TEXT, "
                + "resume_id TEXT, title TEXT, env TEXT, feedback_rating TEXT, feedback_comment TEXT, "
                + "feedback_at TEXT, last_message_at INTEGER, client_ip TEXT, user_id TEXT, user_name TEXT)");
        jdbc.execute("CREATE TABLE chat_message ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT, content TEXT, timestamp TEXT)");
    }

    private void seedData() {
        insertSession("s1", "CLAUDE", "2026-01-01T00:00:00Z", null, "CORRECT", "E10001", "张三", 1700000001000L);
        insertSession("s2", "CLAUDE", "2026-02-02T00:00:00Z", "部署流水线问题", null, "E10002", "李四", 1700000002000L);
        insertSession("s3", "CODEX", "2026-03-03T00:00:00Z", "最新对话", null, "E10003", "王五", 1700000003000L);

        insertMessage("s1", "user", "第一条用户问题", "2026-01-01T00:00:01Z");
        insertMessage("s1", "assistant", "助手回答", "2026-01-01T00:00:02Z");
        insertMessage("s2", "user", "如何配置部署", "2026-02-02T00:00:01Z");
    }

    private void insertSession(String id, String agentType, String createdAt, String title,
                               String feedbackRating, String userId, String userName, Long lastMessageAt) {
        jdbc.update("INSERT INTO chat_session "
                        + "(id, agent_type, working_dir, created_at, title, feedback_rating, "
                        + "feedback_at, last_message_at, client_ip, user_id, user_name) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, agentType, "/tmp", createdAt, title, feedbackRating,
                feedbackRating == null ? null : createdAt, lastMessageAt, "127.0.0.1", userId, userName);
    }

    private void insertMessage(String sessionId, String role, String content, String timestamp) {
        jdbc.update("INSERT INTO chat_message (session_id, role, content, timestamp) VALUES (?,?,?,?)",
                sessionId, role, content, timestamp);
    }
}
