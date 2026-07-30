package com.example.agentweb.infra.chatrun;

import com.example.agentweb.domain.chatrun.*;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqliteToolInvocationRepositoryTest {

    @TempDir Path tempDir;
    private JdbcTemplate jdbc;
    private SqliteToolInvocationRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE chat_tool_invocation (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "session_id TEXT NOT NULL,run_id TEXT,assistant_message_id INTEGER,provider TEXT NOT NULL,"
                + "provider_call_id TEXT,invocation_index INTEGER NOT NULL,invocation_kind TEXT NOT NULL,"
                + "tool_name TEXT,skill_name TEXT,trigger_source TEXT NOT NULL,input_json TEXT,output_text TEXT,"
                + "status TEXT NOT NULL,is_error INTEGER NOT NULL,exit_code INTEGER,provider_item_type TEXT,"
                + "provider_status TEXT,input_truncated INTEGER NOT NULL,output_truncated INTEGER NOT NULL,"
                + "output_original_size INTEGER,started_at INTEGER,completed_at INTEGER,created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,source TEXT NOT NULL,source_message_id INTEGER,migration_confidence TEXT)");
        jdbc.execute("CREATE UNIQUE INDEX uk_live ON chat_tool_invocation(run_id,provider_call_id) "
                + "WHERE run_id IS NOT NULL AND provider_call_id IS NOT NULL");
        repository = new SqliteToolInvocationRepository(jdbc);
    }

    @Test
    void save_shouldUpsertAndPreserveCodexNullToolName() {
        repository.save(invocation(ToolInvocationStatus.STARTED, null));
        repository.save(invocation(ToolInvocationStatus.SUCCEEDED, "ok"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM chat_tool_invocation", Integer.class));
        ToolInvocation saved = repository.findByRunId("run-1", 10, 0).get(0);
        assertEquals(ToolInvocationKind.COMMAND_EXECUTION, saved.getInvocationKind());
        assertNull(saved.getToolName());
        assertEquals("ok", saved.getOutputText());
    }

    @Test
    void save_shouldRetrySqliteSharedCacheLock() {
        JdbcTemplate lockingJdbc = mock(JdbcTemplate.class);
        when(lockingJdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new UncategorizedSQLException("save tool invocation", "INSERT",
                        new SQLiteException(SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE.message,
                                SQLiteErrorCode.SQLITE_LOCKED_SHAREDCACHE)))
                .thenReturn(1);
        SqliteToolInvocationRepository lockingRepository =
                new SqliteToolInvocationRepository(lockingJdbc);

        lockingRepository.save(invocation(ToolInvocationStatus.SUCCEEDED, "ok"));

        verify(lockingJdbc, times(2)).update(anyString(), any(Object[].class));
    }

    private ToolInvocation invocation(ToolInvocationStatus status, String output) {
        return ToolInvocation.builder().sessionId("session-1").runId("run-1").provider(AgentType.CODEX)
                .providerCallId("item-1").invocationIndex(1).invocationKind(ToolInvocationKind.COMMAND_EXECUTION)
                .triggerSource(ToolInvocationTriggerSource.AGENT).inputJson("{\"command\":\"bash x\"}")
                .outputText(output).status(status).providerItemType("command_execution")
                .inputTruncated(false).outputTruncated(false).startedAt(1L).createdAt(1L).updatedAt(2L)
                .source(ToolInvocationSource.LIVE).build();
    }
}
