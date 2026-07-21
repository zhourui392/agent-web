package com.example.agentweb.infra.metrics;

import com.example.agentweb.app.metrics.DailyTrendPoint;
import com.example.agentweb.app.metrics.MetricsOverview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 指标查询服务的 Infra 轻量集成测试:真实 SQLite + @TempDir,不起 Spring,验会话聚合 SQL 正确性。
 * 诊断/工单维度已随子系统摘除,现仅覆盖会话维度。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public class SqliteMetricsQueryServiceTest {

    @TempDir
    Path tempDir;

    private SqliteMetricsQueryService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("metrics-test.db"));
        jdbc = new JdbcTemplate(ds);
        service = new SqliteMetricsQueryService(jdbc);
        createTables();
        seedData();
    }

    @Test
    void overview_chat_countsDistributionAndAccuracy() {
        MetricsOverview.Chat chat = service.overview().getChat();

        assertEquals(3L, chat.getTotal());
        assertEquals(2L, chat.getByAgentType().get("CLAUDE"));
        assertEquals(1L, chat.getByAgentType().get("CODEX"));
        assertEquals(1L, chat.getFeedback().get("CORRECT"));
        assertEquals(1L, chat.getFeedback().get("INCORRECT"));
        // CORRECT 1 / 已评分 2 = 0.5
        assertEquals(0.5, chat.getAccuracyRate(), 1e-9);
    }

    @Test
    void overview_emptyDb_ratesAreNull() {
        jdbc.update("DELETE FROM chat_session");

        MetricsOverview overview = service.overview();

        assertNull(overview.getChat().getAccuracyRate());
    }

    @Test
    void trend_fillsRequestedDaysAndSumsMatchInserts() {
        List<DailyTrendPoint> points = service.trend(7);

        assertEquals(7, points.size());
        long chatSum = points.stream().mapToLong(DailyTrendPoint::getChatCount).sum();
        assertEquals(3L, chatSum);
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE chat_session ("
                + "id TEXT PRIMARY KEY, agent_type TEXT, created_at TEXT, feedback_rating TEXT)");
    }

    private void seedData() {
        String nowIso = Instant.now().toString();

        jdbc.update("INSERT INTO chat_session VALUES (?,?,?,?)", "c1", "CLAUDE", nowIso, "CORRECT");
        jdbc.update("INSERT INTO chat_session VALUES (?,?,?,?)", "c2", "CLAUDE", nowIso, "INCORRECT");
        jdbc.update("INSERT INTO chat_session VALUES (?,?,?,?)", "c3", "CODEX", nowIso, null);
    }
}
