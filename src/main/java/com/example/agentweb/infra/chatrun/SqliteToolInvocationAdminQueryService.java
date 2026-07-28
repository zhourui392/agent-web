package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ToolInvocationAdminFilter;
import com.example.agentweb.app.chatrun.ToolInvocationAdminQueryService;
import com.example.agentweb.domain.chatrun.ToolInvocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SqliteToolInvocationAdminQueryService implements ToolInvocationAdminQueryService {
    private static final int SUMMARY_LENGTH = 180;
    private final JdbcTemplate jdbc;
    private final SqliteToolInvocationRepository.InvocationRowMapper rowMapper =
            new SqliteToolInvocationRepository.InvocationRowMapper();

    public SqliteToolInvocationAdminQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ToolInvocationAdminPage findPage(ToolInvocationAdminFilter filter) {
        SqlFilter sqlFilter = buildFilter(filter);
        long total = jdbc.queryForObject("SELECT count(*) FROM chat_tool_invocation" + sqlFilter.where,
                Long.class, sqlFilter.arguments.toArray());
        List<Object> pageArguments = new ArrayList<Object>(sqlFilter.arguments);
        pageArguments.add(filter.getSize());
        pageArguments.add((filter.getPage() - 1) * filter.getSize());
        List<ToolInvocationAdminRow> items = jdbc.query("SELECT * FROM chat_tool_invocation"
                        + sqlFilter.where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    ToolInvocation value = rowMapper.mapRow(rs, rowNum);
                    return new ToolInvocationAdminRow(value, summarize(value.getInputJson()),
                            summarize(value.getOutputText()));
                }, pageArguments.toArray());
        return new ToolInvocationAdminPage(items, total, filter.getPage(), filter.getSize());
    }

    @Override
    public Map<String, Long> overview() {
        Map<String, Long> result = new LinkedHashMap<String, Long>();
        result.put("total", count("1=1"));
        result.put("claude", count("provider='CLAUDE'"));
        result.put("codex", count("provider='CODEX'"));
        result.put("commandExecutions", count("invocation_kind='COMMAND_EXECUTION'"));
        result.put("skills", count("invocation_kind='SKILL'"));
        result.put("failed", count("status='FAILED'"));
        result.put("incomplete", count("status='INCOMPLETE'"));
        return result;
    }

    @Override
    public ToolInvocation findById(long id) {
        List<ToolInvocation> rows = jdbc.query("SELECT * FROM chat_tool_invocation WHERE id=?",
                rowMapper, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long count(String predicate) {
        return jdbc.queryForObject("SELECT count(*) FROM chat_tool_invocation WHERE " + predicate, Long.class);
    }

    private SqlFilter buildFilter(ToolInvocationAdminFilter filter) {
        List<String> clauses = new ArrayList<String>();
        List<Object> arguments = new ArrayList<Object>();
        equalsFilter(clauses, arguments, "provider", filter.getProvider());
        equalsFilter(clauses, arguments, "invocation_kind", filter.getInvocationKind());
        equalsFilter(clauses, arguments, "status", filter.getStatus());
        equalsFilter(clauses, arguments, "trigger_source", filter.getTriggerSource());
        equalsFilter(clauses, arguments, "session_id", filter.getSessionId());
        equalsFilter(clauses, arguments, "run_id", filter.getRunId());
        containsFilter(clauses, arguments, "tool_name", filter.getToolName());
        containsFilter(clauses, arguments, "skill_name", filter.getSkillName());
        if (filter.getStartedAfter() != null) {
            clauses.add("started_at>=?");
            arguments.add(filter.getStartedAfter());
        }
        if (filter.getStartedBefore() != null) {
            clauses.add("started_at<=?");
            arguments.add(filter.getStartedBefore());
        }
        return new SqlFilter(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), arguments);
    }

    private void equalsFilter(List<String> clauses, List<Object> arguments, String column, String value) {
        if (value != null && !value.trim().isEmpty()) {
            clauses.add(column + "=?");
            arguments.add(value.trim());
        }
    }

    private void containsFilter(List<String> clauses, List<Object> arguments, String column, String value) {
        if (value != null && !value.trim().isEmpty()) {
            clauses.add(column + " LIKE ? ESCAPE '\\'");
            arguments.add("%" + escapeLike(value.trim()) + "%");
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String summarize(String value) {
        if (value == null) return null;
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= SUMMARY_LENGTH ? compact : compact.substring(0, SUMMARY_LENGTH) + "…";
    }

    private static final class SqlFilter {
        private final String where;
        private final List<Object> arguments;
        private SqlFilter(String where, List<Object> arguments) {
            this.where = where;
            this.arguments = arguments;
        }
    }
}
