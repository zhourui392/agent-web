package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ShellCommandToolNameResolver;
import com.example.agentweb.app.chatrun.ToolInvocationAdminFilter;
import com.example.agentweb.app.chatrun.ToolInvocationAdminQueryService.ToolInvocationAdminPage;
import com.example.agentweb.domain.chatrun.ToolInvocation;
import com.example.agentweb.domain.chatrun.ToolInvocationKind;
import com.example.agentweb.domain.chatrun.ToolInvocationSource;
import com.example.agentweb.domain.chatrun.ToolInvocationStatus;
import com.example.agentweb.domain.chatrun.ToolInvocationTriggerSource;
import com.example.agentweb.domain.shared.AgentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteToolInvocationAdminQueryServiceTest {
    @TempDir Path tempDir;
    private SqliteToolInvocationRepository repository;
    private SqliteToolInvocationAdminQueryService queryService;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE chat_tool_invocation (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "session_id TEXT NOT NULL,run_id TEXT,assistant_message_id INTEGER,provider TEXT NOT NULL,"
                + "provider_call_id TEXT,invocation_index INTEGER NOT NULL,invocation_kind TEXT NOT NULL,"
                + "tool_name TEXT,skill_name TEXT,trigger_source TEXT NOT NULL,input_json TEXT,output_text TEXT,"
                + "status TEXT NOT NULL,is_error INTEGER NOT NULL,exit_code INTEGER,provider_item_type TEXT,"
                + "provider_status TEXT,input_truncated INTEGER NOT NULL,output_truncated INTEGER NOT NULL,"
                + "output_original_size INTEGER,started_at INTEGER,completed_at INTEGER,created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL,source TEXT NOT NULL,source_message_id INTEGER,migration_confidence TEXT)");
        repository = new SqliteToolInvocationRepository(jdbc);
        queryService = new SqliteToolInvocationAdminQueryService(jdbc,
                new ShellCommandToolNameResolver(new ObjectMapper()));
    }

    @Test
    void findPage_shouldExposeAndFilterShellCommandNameAcrossProviders() {
        repository.save(invocation("item-1", AgentType.CODEX, ToolInvocationKind.COMMAND_EXECUTION,
                null, "{\"command\":\"/bin/bash -lc 'cd /workspace && rg -n tool src'\"}", 4L));
        repository.save(invocation("bash-1", AgentType.CLAUDE, ToolInvocationKind.TOOL_USE,
                "Bash", "{\"command\":\"/bin/bash -lc 'mvn test'\"}", 3L));
        repository.save(invocation("bash-2", AgentType.CLAUDE, ToolInvocationKind.TOOL_USE,
                "Bash", "{\"other\":true}", 2L));
        repository.save(invocation("tool-1", AgentType.CLAUDE, ToolInvocationKind.TOOL_USE,
                "Read", "{\"file_path\":\"README.md\"}", 1L));

        ToolInvocationAdminPage all = queryService.findPage(filter(null));
        assertEquals("rg", all.getItems().get(0).getDisplayToolName());
        assertEquals("mvn", all.getItems().get(1).getDisplayToolName());
        assertEquals("Bash", all.getItems().get(2).getDisplayToolName());
        assertEquals("Read", all.getItems().get(3).getDisplayToolName());

        ToolInvocationAdminPage filtered = queryService.findPage(filter("mvn"));
        assertEquals(1, filtered.getTotal());
        assertEquals("mvn", filtered.getItems().get(0).getDisplayToolName());
    }

    @Test
    void findPage_shouldKeepShellNameForClaudeComplexScript() {
        repository.save(invocation("bash-complex", AgentType.CLAUDE, ToolInvocationKind.TOOL_USE,
                "Bash", "{\"command\":\"bash -lc 'result=$(git status); printf %s result'\"}", 1L));

        assertEquals("bash", queryService.findPage(filter(null)).getItems().get(0).getDisplayToolName());
    }

    private ToolInvocationAdminFilter filter(String toolName) {
        return ToolInvocationAdminFilter.builder().page(1).size(20).toolName(toolName).build();
    }

    private ToolInvocation invocation(String callId, AgentType provider, ToolInvocationKind kind,
                                      String toolName, String input, long createdAt) {
        return ToolInvocation.builder().sessionId("session-1").runId("run-" + callId).provider(provider)
                .providerCallId(callId).invocationIndex(1).invocationKind(kind).toolName(toolName)
                .triggerSource(ToolInvocationTriggerSource.AGENT).inputJson(input)
                .status(ToolInvocationStatus.SUCCEEDED).error(false).inputTruncated(false)
                .outputTruncated(false).startedAt(createdAt).createdAt(createdAt).updatedAt(createdAt)
                .source(ToolInvocationSource.LIVE).build();
    }
}
