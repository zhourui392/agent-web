package com.example.agentweb.infra.metrics;

import com.example.agentweb.app.metrics.DailyTrendPoint;
import com.example.agentweb.app.metrics.MetricsOverview;
import com.example.agentweb.app.metrics.MetricsQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link MetricsQueryService} 的 SQLite 实现:对 chat_session 做 SELECT...GROUP BY 聚合,
 * 组装为只读 DTO。
 *
 * <p>读侧投影:不经聚合根、不返回半截聚合,SQL 内不含业务判断(分类/比率在 Java 侧由分布算出)。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Component
public class SqliteMetricsQueryService implements MetricsQueryService {

    private static final String FEEDBACK_CORRECT = "CORRECT";
    private final JdbcTemplate jdbc;

    public SqliteMetricsQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public MetricsOverview overview() {
        MetricsOverview overview = new MetricsOverview();
        fillChat(overview.getChat());
        return overview;
    }

    private void fillChat(MetricsOverview.Chat chat) {
        chat.setTotal(scalarLong("SELECT COUNT(*) FROM chat_session"));
        chat.setByAgentType(groupCount("SELECT agent_type, COUNT(*) FROM chat_session GROUP BY agent_type"));
        Map<String, Long> feedback = groupCount(
                "SELECT feedback_rating, COUNT(*) FROM chat_session WHERE feedback_rating IS NOT NULL GROUP BY feedback_rating");
        chat.setFeedback(feedback);
        chat.setAccuracyRate(ratio(feedback.getOrDefault(FEEDBACK_CORRECT, 0L), sum(feedback)));
    }

    @Override
    public List<DailyTrendPoint> trend(int days) {
        LocalDate firstDay = LocalDate.now(ZoneOffset.UTC).minusDays(days - 1L);
        long cutoffSeconds = firstDay.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
        String cutoffIso = Instant.ofEpochSecond(cutoffSeconds).toString();
        Map<String, Long> chatByDay = groupCount(
                "SELECT substr(created_at, 1, 10), COUNT(*) FROM chat_session WHERE created_at >= ? GROUP BY 1",
                cutoffIso);

        List<DailyTrendPoint> points = new ArrayList<>(days);
        for (int offset = days - 1; offset >= 0; offset--) {
            String day = firstDay.plusDays(days - 1L - offset).toString();
            points.add(new DailyTrendPoint(day, chatByDay.getOrDefault(day, 0L)));
        }
        return points;
    }

    private long scalarLong(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private Map<String, Long> groupCount(String sql, Object... args) {
        Map<String, Long> result = new LinkedHashMap<>();
        RowCallbackHandler handler = rs -> {
            String key = rs.getString(1);
            result.put(key == null ? "unknown" : key, rs.getLong(2));
        };
        jdbc.query(sql, handler, args);
        return result;
    }

    private long sum(Map<String, Long> counts) {
        long total = 0L;
        for (Long v : counts.values()) {
            total += v;
        }
        return total;
    }

    /** 占比;分母为 0 时返回 null(样本不足,前端显示"—")。 */
    private Double ratio(long numerator, long denominator) {
        return denominator == 0L ? null : (double) numerator / denominator;
    }
}
