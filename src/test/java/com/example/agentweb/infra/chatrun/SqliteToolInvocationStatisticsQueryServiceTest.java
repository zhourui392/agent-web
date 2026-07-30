package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ShellCommandToolNameResolver;
import com.example.agentweb.app.chatrun.ToolInvocationStatisticsQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author alex
 */
class SqliteToolInvocationStatisticsQueryServiceTest {
    @TempDir Path tempDir;
    private JdbcTemplate jdbc;
    private SqliteToolInvocationStatisticsQueryService service;

    @BeforeEach void setUp() {
        SQLiteDataSource dataSource=new SQLiteDataSource(); dataSource.setUrl("jdbc:sqlite:"+tempDir.resolve("statistics.db"));
        jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE chat_tool_invocation (id INTEGER PRIMARY KEY,session_id TEXT,run_id TEXT,provider TEXT,invocation_kind TEXT,tool_name TEXT,skill_name TEXT,trigger_source TEXT,input_json TEXT,status TEXT,input_truncated INTEGER,output_truncated INTEGER,started_at INTEGER,completed_at INTEGER,source TEXT)");
        jdbc.execute("CREATE TABLE chat_session (id TEXT PRIMARY KEY,title TEXT,user_id TEXT,user_name TEXT,agent_type TEXT)");
        jdbc.update("INSERT INTO chat_session VALUES ('s1','First','u1','User','CLAUDE'),('s2','Second',NULL,NULL,'CODEX')");
        insert(1,"s1","CLAUDE","TOOL_USE","Bash",null,"SUCCEEDED",1000L,1000L,"HISTORY_MIGRATION",0);
        insert(2,"s1","CLAUDE","TOOL_USE","Bash",null,"FAILED",2000L,2200L,"LIVE",1);
        insert(3,"s1","CLAUDE","SKILL","Skill","issue-log","INCOMPLETE",3000L,null,"HISTORY_MIGRATION",0);
        insert(4,"s2","CODEX","COMMAND_EXECUTION",null,null,"SUCCEEDED",4000L,4500L,"LIVE",0);
        service=new SqliteToolInvocationStatisticsQueryService(jdbc,new ShellCommandToolNameResolver(new ObjectMapper()));
    }

    @Test void overview_shouldUseTerminalDenominatorAndSourceSemantics() {
        ToolInvocationStatisticsQueryService.Overview value=service.overview(filter());
        assertEquals(4,value.getInvocationCount()); assertEquals(2,value.getConversationCount());
        assertEquals(2,value.getSucceededCount()); assertEquals(1,value.getFailedCount()); assertEquals(1,value.getIncompleteCount());
        assertEquals(.5,value.getSuccessRate(),.0001); assertEquals(2,value.getLiveCount()); assertEquals(2,value.getHistoryMigrationCount());
        assertEquals(2,value.getDurationAvailableCount()); assertEquals(1,value.getOutputTruncatedCount());
    }

    @Test void rankings_shouldSeparateToolsSkillsAndNormalizedCommands() {
        assertEquals("Bash",service.rankings(filter(),ToolInvocationStatisticsQueryService.RankingType.TOOL,
                ToolInvocationStatisticsQueryService.RankingOrder.INVOCATION_COUNT_DESC,1,20).getItems().get(0).getAnalysisName());
        assertEquals("issue-log",service.rankings(filter(),ToolInvocationStatisticsQueryService.RankingType.SKILL,
                ToolInvocationStatisticsQueryService.RankingOrder.INVOCATION_COUNT_DESC,1,20).getItems().get(0).getAnalysisName());
        assertEquals("git",service.rankings(filter(),ToolInvocationStatisticsQueryService.RankingType.COMMAND,
                ToolInvocationStatisticsQueryService.RankingOrder.INVOCATION_COUNT_DESC,1,20).getItems().get(0).getAnalysisName());
    }

    @Test void conversations_shouldJoinMetadataAndRespectExclusiveEnd() {
        ToolInvocationStatisticsQueryService.Filter range=ToolInvocationStatisticsQueryService.Filter.builder().startedAfter(1000L).startedBefore(4000L).build();
        ToolInvocationStatisticsQueryService.Page<ToolInvocationStatisticsQueryService.ConversationRow> page=service.conversations(range,
                ToolInvocationStatisticsQueryService.ConversationOrder.INVOCATION_COUNT_DESC,1,20);
        assertEquals(1,page.getTotal()); assertEquals("First",page.getItems().get(0).getTitle()); assertEquals(3,page.getItems().get(0).getInvocationCount());
    }

    private ToolInvocationStatisticsQueryService.Filter filter(){return ToolInvocationStatisticsQueryService.Filter.builder().build();}
    private void insert(int id,String session,String provider,String kind,String tool,String skill,String status,Long started,Long completed,String source,int truncated){
        jdbc.update("INSERT INTO chat_tool_invocation (id,session_id,provider,invocation_kind,tool_name,skill_name,"
                        + "trigger_source,input_json,status,input_truncated,output_truncated,started_at,completed_at,source) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,session,provider,kind,tool,skill,"AGENT","{\"command\":\"/bin/bash -lc 'git status'\"}",
                status,0,truncated,started,completed,source);
    }
}
