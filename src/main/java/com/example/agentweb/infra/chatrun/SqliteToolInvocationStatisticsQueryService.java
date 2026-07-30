package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ShellCommandToolNameResolver;
import com.example.agentweb.app.chatrun.ToolInvocationStatisticsQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author alex
 */
@Service
public class SqliteToolInvocationStatisticsQueryService implements ToolInvocationStatisticsQueryService {
    private static final ZoneId STATISTICS_ZONE = ZoneId.systemDefault();
    private final JdbcTemplate jdbc;
    private final ShellCommandToolNameResolver commandNameResolver;

    public SqliteToolInvocationStatisticsQueryService(JdbcTemplate jdbc,
                                                       ShellCommandToolNameResolver commandNameResolver) {
        this.jdbc = jdbc;
        this.commandNameResolver = commandNameResolver;
    }

    @Override
    public Overview overview(Filter filter) {
        SqlFilter sql = filter(filter, null, false);
        return jdbc.queryForObject("SELECT COUNT(*),COUNT(DISTINCT session_id),"
                        + sum("status='SUCCEEDED'") + "," + sum("status='FAILED'") + ","
                        + sum("status='INCOMPLETE'") + "," + sum("status='STARTED'") + ","
                        + sum("status='UNKNOWN'") + "," + sum("provider='CLAUDE'") + ","
                        + sum("provider='CODEX'") + "," + sum("provider='NATIVE'") + ","
                        + sum("invocation_kind='TOOL_USE'") + "," + sum("invocation_kind='COMMAND_EXECUTION'") + ","
                        + sum("invocation_kind='SKILL'") + ",COALESCE(SUM(input_truncated),0),"
                        + "COALESCE(SUM(output_truncated),0)," + sum("source='LIVE'") + ","
                        + sum("source='HISTORY_MIGRATION'") + ","
                        + sum("source='LIVE' AND started_at IS NOT NULL AND completed_at>=started_at")
                        + " FROM chat_tool_invocation" + sql.where,
                (rs, rowNum) -> mapOverview(rs), sql.arguments.toArray());
    }

    @Override
    public List<DailyPoint> dailyTrend(Filter filter) {
        SqlFilter sql = filter(filter, null, false);
        List<DailyPoint> found = jdbc.query("SELECT date(started_at/1000,'unixepoch','localtime') day,COUNT(*),"
                        + "COUNT(DISTINCT session_id)," + sum("status='SUCCEEDED'") + ","
                        + sum("status='FAILED'") + "," + sum("status='INCOMPLETE'") + ","
                        + sum("provider='CLAUDE'") + "," + sum("provider='CODEX'")
                        + " FROM chat_tool_invocation" + sql.where + " GROUP BY day ORDER BY day",
                (rs, n) -> DailyPoint.builder().date(rs.getString(1)).invocationCount(rs.getLong(2))
                        .conversationCount(rs.getLong(3)).succeededCount(rs.getLong(4)).failedCount(rs.getLong(5))
                        .incompleteCount(rs.getLong(6)).claudeCount(rs.getLong(7)).codexCount(rs.getLong(8)).build(),
                sql.arguments.toArray());
        if (filter.getStartedAfter() == null || filter.getStartedBefore() == null) return found;
        Map<String, DailyPoint> byDate = new HashMap<>();
        for (DailyPoint point : found) byDate.put(point.getDate(), point);
        LocalDate first = Instant.ofEpochMilli(filter.getStartedAfter()).atZone(STATISTICS_ZONE).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(filter.getStartedBefore()).atZone(STATISTICS_ZONE).toLocalDate();
        List<DailyPoint> result = new ArrayList<>();
        for (LocalDate day = first; day.isBefore(end); day = day.plusDays(1)) {
            result.add(byDate.getOrDefault(day.toString(), DailyPoint.builder().date(day.toString()).build()));
        }
        return result;
    }

    @Override
    public Page<RankingRow> rankings(Filter filter, RankingType type, RankingOrder order, int page, int size) {
        List<RankingRow> all = type == RankingType.COMMAND ? commandRankings(filter) : sqlRankings(filter, type);
        all.sort(rankingComparator(order));
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new Page<>(all.subList(from, to), all.size(), page, size);
    }

    @Override
    public Page<ConversationRow> conversations(Filter filter, ConversationOrder order, int page, int size) {
        SqlFilter sql = filter(filter, null, false);
        String ordering = order == ConversationOrder.FAILED_COUNT_DESC ? "failed_count DESC"
                : order == ConversationOrder.FAILURE_RATE_DESC
                ? "CASE WHEN terminal_count=0 THEN -1.0 ELSE failed_count*1.0/terminal_count END DESC"
                : "invocation_count DESC";
        long total = jdbc.queryForObject("SELECT COUNT(DISTINCT i.session_id) FROM chat_tool_invocation i"
                + aliasWhere(sql.where, "i."), Long.class, sql.arguments.toArray());
        List<Object> args = new ArrayList<>(sql.arguments); args.add(size); args.add((page - 1) * size);
        String query = "SELECT i.session_id,s.title,s.user_id,s.user_name,s.agent_type,COUNT(*) invocation_count,"
                + "COUNT(DISTINCT CASE WHEN i.invocation_kind='TOOL_USE' THEN COALESCE(i.tool_name,'未知工具') "
                + "WHEN i.invocation_kind='SKILL' THEN COALESCE(i.skill_name,'未识别 Skill') ELSE '命令执行' END),"
                + sum("i.invocation_kind='SKILL'") + " skill_count," + sum("i.status='SUCCEEDED'") + " succeeded_count,"
                + sum("i.status='FAILED'") + " failed_count," + sum("i.status='INCOMPLETE'") + " incomplete_count,"
                + sum("i.status IN ('SUCCEEDED','FAILED','INCOMPLETE','UNKNOWN')") + " terminal_count,"
                + "MIN(i.started_at),MAX(i.started_at) FROM chat_tool_invocation i JOIN chat_session s ON s.id=i.session_id"
                + aliasWhere(sql.where, "i.") + " GROUP BY i.session_id,s.title,s.user_id,s.user_name,s.agent_type ORDER BY "
                + ordering + ",i.session_id LIMIT ? OFFSET ?";
        List<ConversationRow> rows = jdbc.query(query, this::mapConversation, args.toArray());
        return new Page<>(rows, total, page, size);
    }

    private List<RankingRow> sqlRankings(Filter filter, RankingType type) {
        String kind = type == RankingType.TOOL ? "TOOL_USE" : "SKILL";
        String expression = type == RankingType.TOOL ? "COALESCE(tool_name,'未知工具')" : "COALESCE(skill_name,'未识别 Skill')";
        SqlFilter sql = filter(filter, kind, false);
        return jdbc.query("SELECT " + expression + ",COUNT(*),COUNT(DISTINCT session_id),"
                        + sum("status='SUCCEEDED'") + "," + sum("status='FAILED'") + ","
                        + sum("status='INCOMPLETE'") + "," + sum("status='UNKNOWN'") + ","
                        + "COALESCE(SUM(input_truncated),0),COALESCE(SUM(output_truncated),0) FROM chat_tool_invocation"
                        + sql.where + " GROUP BY " + expression,
                (rs, n) -> ranking(rs.getString(1), kind, rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9)),
                sql.arguments.toArray());
    }

    private List<RankingRow> commandRankings(Filter filter) {
        SqlFilter sql = filter(filter, "COMMAND_EXECUTION", true);
        Map<String, MutableRanking> grouped = new LinkedHashMap<>();
        jdbc.query("SELECT session_id,status,input_json,input_truncated,output_truncated FROM chat_tool_invocation"
                        + sql.where, rs -> {
                    String name = commandNameResolver.resolveInputJson(rs.getString(3));
                    name = name == null ? "Unknown" : name;
                    if (filter.getAnalysisName() != null && !name.equalsIgnoreCase(filter.getAnalysisName())) return;
                    grouped.computeIfAbsent(name, ignored -> new MutableRanking()).add(rs);
                }, sql.arguments.toArray());
        List<RankingRow> result = new ArrayList<>();
        for (Map.Entry<String, MutableRanking> entry : grouped.entrySet()) result.add(entry.getValue().toRow(entry.getKey()));
        return result;
    }

    private SqlFilter filter(Filter filter, String forcedKind, boolean omitAnalysisName) {
        List<String> clauses = new ArrayList<>(); List<Object> args = new ArrayList<>();
        if (filter.getStartedAfter() != null) { clauses.add("started_at>=?"); args.add(filter.getStartedAfter()); }
        if (filter.getStartedBefore() != null) { clauses.add("started_at<?"); args.add(filter.getStartedBefore()); }
        equal(clauses, args, "provider", filter.getProvider());
        equal(clauses, args, "invocation_kind", forcedKind == null ? filter.getInvocationKind() : forcedKind);
        equal(clauses, args, "status", filter.getStatus()); equal(clauses, args, "source", filter.getSource());
        equal(clauses, args, "trigger_source", filter.getTriggerSource()); equal(clauses, args, "session_id", filter.getSessionId());
        equal(clauses, args, "run_id", filter.getRunId());
        if (!omitAnalysisName && filter.getAnalysisName() != null) {
            String column = "SKILL".equals(forcedKind) ? "skill_name" : "tool_name";
            clauses.add("LOWER(" + column + ")=LOWER(?)"); args.add(filter.getAnalysisName());
        }
        return new SqlFilter(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), args);
    }

    private void equal(List<String> clauses, List<Object> args, String column, String value) {
        if (value != null && !value.trim().isEmpty()) { clauses.add(column + "=?"); args.add(value.trim()); }
    }
    private String aliasWhere(String where, String alias) {
        if (where.isEmpty()) return where;
        String qualified = where;
        for (String column : new String[]{"started_at", "provider", "invocation_kind", "status", "source",
                "trigger_source", "session_id", "run_id", "tool_name", "skill_name"}) {
            qualified = qualified.replaceAll("\\b" + column + "\\b", alias + column);
        }
        return qualified;
    }
    private String sum(String condition) { return "COALESCE(SUM(CASE WHEN " + condition + " THEN 1 ELSE 0 END),0)"; }
    private Double rate(long numerator, long denominator) { return denominator == 0 ? null : numerator * 1.0 / denominator; }

    private Overview mapOverview(ResultSet rs) throws SQLException {
        long total=rs.getLong(1), conversations=rs.getLong(2), success=rs.getLong(3), failed=rs.getLong(4),
                incomplete=rs.getLong(5), started=rs.getLong(6), unknown=rs.getLong(7), terminal=success+failed+incomplete+unknown;
        return Overview.builder().invocationCount(total).conversationCount(conversations)
                .averageInvocationsPerConversation(conversations == 0 ? null : total * 1.0 / conversations)
                .terminalCount(terminal).succeededCount(success).failedCount(failed).incompleteCount(incomplete)
                .startedCount(started).unknownCount(unknown).successRate(rate(success,terminal)).failureRate(rate(failed,terminal))
                .incompleteRate(rate(incomplete,terminal)).claudeCount(rs.getLong(8)).codexCount(rs.getLong(9))
                .nativeCount(rs.getLong(10)).toolUseCount(rs.getLong(11)).commandExecutionCount(rs.getLong(12))
                .skillCount(rs.getLong(13)).inputTruncatedCount(rs.getLong(14)).outputTruncatedCount(rs.getLong(15))
                .liveCount(rs.getLong(16)).historyMigrationCount(rs.getLong(17)).durationAvailableCount(rs.getLong(18)).build();
    }
    private RankingRow ranking(String name,String kind,long count,long conversations,long success,long failed,long incomplete,
                               long unknown,long inputTruncated,long outputTruncated) {
        long terminal=success+failed+incomplete+unknown;
        return RankingRow.builder().analysisName(name).invocationKind(kind).invocationCount(count)
                .conversationCount(conversations).terminalCount(terminal).succeededCount(success).failedCount(failed)
                .incompleteCount(incomplete).unknownCount(unknown).failureRate(rate(failed,terminal))
                .inputTruncatedCount(inputTruncated).outputTruncatedCount(outputTruncated).build();
    }
    private ConversationRow mapConversation(ResultSet rs,int row) throws SQLException {
        long failed=rs.getLong(10), terminal=rs.getLong(12);
        return ConversationRow.builder().sessionId(rs.getString(1)).title(rs.getString(2)).userId(rs.getString(3))
                .userName(rs.getString(4)).agentType(rs.getString(5)).invocationCount(rs.getLong(6))
                .distinctAnalysisNameCount(rs.getLong(7)).skillCount(rs.getLong(8)).succeededCount(rs.getLong(9))
                .failedCount(failed).incompleteCount(rs.getLong(11)).terminalCount(terminal).failureRate(rate(failed,terminal))
                .firstInvocationAt(nullableLong(rs,13)).lastInvocationAt(nullableLong(rs,14)).build();
    }
    private Long nullableLong(ResultSet rs,int column) throws SQLException { long value=rs.getLong(column); return rs.wasNull()?null:value; }
    private Comparator<RankingRow> rankingComparator(RankingOrder order) {
        Comparator<RankingRow> result;
        if (order == RankingOrder.CONVERSATION_COUNT_DESC) result=Comparator.comparingLong(RankingRow::getConversationCount).reversed();
        else if (order == RankingOrder.FAILED_COUNT_DESC) result=Comparator.comparingLong(RankingRow::getFailedCount).reversed();
        else if (order == RankingOrder.FAILURE_RATE_DESC) result=Comparator.comparing(r -> r.getFailureRate()==null?-1.0:r.getFailureRate(),Comparator.reverseOrder());
        else result=Comparator.comparingLong(RankingRow::getInvocationCount).reversed();
        return result.thenComparing(RankingRow::getAnalysisName,String.CASE_INSENSITIVE_ORDER);
    }

    private final class MutableRanking {
        long count,success,failed,incomplete,unknown,inputTruncated,outputTruncated; final java.util.Set<String> sessions=new java.util.HashSet<>();
        void add(ResultSet rs) throws SQLException { count++; sessions.add(rs.getString(1)); String status=rs.getString(2);
            if("SUCCEEDED".equals(status))success++; else if("FAILED".equals(status))failed++; else if("INCOMPLETE".equals(status))incomplete++; else if("UNKNOWN".equals(status))unknown++;
            inputTruncated+=rs.getInt(4); outputTruncated+=rs.getInt(5); }
        RankingRow toRow(String name){return ranking(name,"COMMAND_EXECUTION",count,sessions.size(),success,failed,incomplete,unknown,inputTruncated,outputTruncated);}
    }
    private static final class SqlFilter { final String where; final List<Object> arguments; SqlFilter(String where,List<Object> arguments){this.where=where;this.arguments=arguments;} }
}
