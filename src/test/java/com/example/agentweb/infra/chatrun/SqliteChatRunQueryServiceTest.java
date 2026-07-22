package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ActiveChatRunView;
import com.example.agentweb.app.chatrun.ChatRunExecutionContext;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class SqliteChatRunQueryServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteChatRunQueryService queryService;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("query.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE chat_session (id TEXT PRIMARY KEY, agent_type TEXT NOT NULL, "
                + "working_dir TEXT NOT NULL, resume_id TEXT, env TEXT, user_id TEXT)");
        jdbc.execute("CREATE TABLE chat_message (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, "
                + "role TEXT NOT NULL, content TEXT NOT NULL, timestamp TEXT NOT NULL)");
        SqliteChatRunRepositoryTest.createSchema(jdbc);
        CurrentUserProvider currentUser = new CurrentUserProvider(new UserContext() {
            @Override
            public Optional<LoginUser> currentUser() {
                return Optional.of(new LoginUser("user-1", "User One", null));
            }
        });
        queryService = new SqliteChatRunQueryService(jdbc, currentUser);
    }

    @Test
    void active_query_should_only_return_current_users_visible_sessions() {
        insertSession("s1", "user-1");
        insertSession("s2", "user-2");
        insertMessage("s1", "user", "q1");
        insertMessage("s2", "user", "q2");
        insertRun("r1", "s1", 1L, "RUNNING", 3L, 1);
        insertRun("r2", "s2", 2L, "RUNNING", 4L, 1);

        List<ActiveChatRunView> active = queryService.findActiveForCurrentUser();

        assertEquals(1, active.size());
        assertEquals("r1", active.get(0).getRunId());
        assertEquals("/workspace/s1", active.get(0).getWorkingDir());
    }

    @Test
    void execution_context_should_include_submitted_message_and_prior_history() {
        insertSession("s1", "user-1");
        insertMessage("s1", "user", "old question");
        insertMessage("s1", "assistant", "old answer");
        insertMessage("s1", "user", "new question");
        insertRun("r1", "s1", 3L, "PENDING", 1L, 0);

        Optional<ChatRunExecutionContext> found = queryService.findExecutionContext("r1");

        assertTrue(found.isPresent());
        ChatRunExecutionContext context = found.get();
        assertEquals("new question", context.getMessage());
        assertEquals(2, context.getHistory().size());
        assertEquals("old answer", context.getHistory().get(1).getContent());
        assertFalse(context.isRecallEnabled());
        assertEquals("CODEX", context.getAgentType().name());
    }

    @Test
    void active_count_should_include_all_users_and_only_active_statuses() {
        insertSession("s1", "user-1");
        insertSession("s2", "user-2");
        insertMessage("s1", "user", "q1");
        insertMessage("s2", "user", "q2");
        insertRun("r1", "s1", 1L, "RUNNING", 1L, 1);
        insertRun("r2", "s2", 2L, "PENDING", 1L, 1);
        jdbc.update("UPDATE chat_run SET status='SUCCEEDED', assistant_message_id=99, "
                + "finished_at=1784714401000 WHERE id='r2'");

        assertEquals(1L, queryService.countActiveRuns());
    }

    @Test
    void active_run_ids_should_return_all_orphans_without_user_filter() {
        insertSession("s1", "user-1");
        insertSession("s2", "user-2");
        insertSession("s3", "user-1");
        insertMessage("s1", "user", "q1");
        insertMessage("s2", "user", "q2");
        insertMessage("s3", "user", "q3");
        insertRun("run-pending", "s1", 1L, "PENDING", 0L, 1);
        insertRun("run-running", "s2", 2L, "RUNNING", 0L, 1);
        insertRun("run-finished", "s3", 3L, "PENDING", 0L, 1);
        jdbc.update("UPDATE chat_run SET status='SUCCEEDED', assistant_message_id=99, "
                + "started_at=1784714400000, finished_at=1784714401000 WHERE id='run-finished'");

        assertEquals(Arrays.asList("run-pending", "run-running"), queryService.findActiveRunIds());
    }

    private void insertSession(String id, String userId) {
        jdbc.update("INSERT INTO chat_session (id, agent_type, working_dir, resume_id, env, user_id) "
                        + "VALUES (?, 'CODEX', ?, NULL, 'test', ?)", id, "/workspace/" + id, userId);
    }

    private void insertMessage(String sessionId, String role, String content) {
        jdbc.update("INSERT INTO chat_message(session_id, role, content, timestamp) VALUES (?,?,?,'2026-07-22T10:00:00Z')",
                sessionId, role, content);
    }

    private void insertRun(String id, String sessionId, long messageId, String status,
                           long lastSeq, int recallEnabled) {
        jdbc.update("INSERT INTO chat_run (id, session_id, user_message_id, idempotency_key, recall_enabled, "
                        + "status, last_event_seq, created_at, updated_at, version) VALUES (?,?,?,?,?,?,?,?,?,0)",
                id, sessionId, messageId, "key-" + id, recallEnabled, status, lastSeq,
                1784714400000L, 1784714400000L);
    }
}
