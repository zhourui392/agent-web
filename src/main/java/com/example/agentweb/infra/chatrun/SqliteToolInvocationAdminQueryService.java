package com.example.agentweb.infra.chatrun;

import com.example.agentweb.app.chatrun.ShellCommandToolNameResolver;
import com.example.agentweb.app.chatrun.ToolInvocationAdminFilter;
import com.example.agentweb.app.chatrun.ToolInvocationAdminQueryService;
import com.example.agentweb.domain.chatrun.ToolInvocation;
import com.example.agentweb.domain.chatrun.ToolInvocationKind;
import com.example.agentweb.domain.shared.AgentType;
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
    private final ShellCommandToolNameResolver commandToolNameResolver;
    private final SqliteToolInvocationRepository.InvocationRowMapper rowMapper =
            new SqliteToolInvocationRepository.InvocationRowMapper();

    public SqliteToolInvocationAdminQueryService(JdbcTemplate jdbc,
                                                  ShellCommandToolNameResolver commandToolNameResolver) {
        this.jdbc = jdbc;
        this.commandToolNameResolver = commandToolNameResolver;
    }

    @Override
    public ToolInvocationAdminPage findPage(ToolInvocationAdminFilter filter) {
        String toolName = trimToNull(filter.getToolName());
        SqlFilter sqlFilter = buildFilter(filter, toolName == null);
        if (toolName != null) {
            List<ToolInvocation> candidates = jdbc.query("SELECT * FROM chat_tool_invocation"
                            + sqlFilter.where + " ORDER BY created_at DESC,id DESC",
                    rowMapper, sqlFilter.arguments.toArray());
            List<ToolInvocation> matches = new ArrayList<ToolInvocation>();
            for (ToolInvocation candidate : candidates) {
                String displayToolName = displayToolName(candidate);
                if (displayToolName != null && displayToolName.toLowerCase().contains(toolName.toLowerCase())) {
                    matches.add(candidate);
                }
            }
            int from = Math.min((filter.getPage() - 1) * filter.getSize(), matches.size());
            int to = Math.min(from + filter.getSize(), matches.size());
            return new ToolInvocationAdminPage(toRows(matches.subList(from, to)), matches.size(),
                    filter.getPage(), filter.getSize());
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM chat_tool_invocation" + sqlFilter.where,
                Long.class, sqlFilter.arguments.toArray());
        List<Object> pageArguments = new ArrayList<Object>(sqlFilter.arguments);
        pageArguments.add(filter.getSize());
        pageArguments.add((filter.getPage() - 1) * filter.getSize());
        List<ToolInvocation> values = jdbc.query("SELECT * FROM chat_tool_invocation"
                        + sqlFilter.where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                rowMapper, pageArguments.toArray());
        return new ToolInvocationAdminPage(toRows(values), total, filter.getPage(), filter.getSize());
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

    private SqlFilter buildFilter(ToolInvocationAdminFilter filter, boolean includeToolName) {
        List<String> clauses = new ArrayList<String>();
        List<Object> arguments = new ArrayList<Object>();
        equalsFilter(clauses, arguments, "provider", filter.getProvider());
        equalsFilter(clauses, arguments, "invocation_kind", filter.getInvocationKind());
        equalsFilter(clauses, arguments, "status", filter.getStatus());
        equalsFilter(clauses, arguments, "trigger_source", filter.getTriggerSource());
        equalsFilter(clauses, arguments, "session_id", filter.getSessionId());
        equalsFilter(clauses, arguments, "run_id", filter.getRunId());
        if (includeToolName) {
            containsFilter(clauses, arguments, "tool_name", filter.getToolName());
        }
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

    private List<ToolInvocationAdminRow> toRows(List<ToolInvocation> values) {
        List<ToolInvocationAdminRow> rows = new ArrayList<ToolInvocationAdminRow>(values.size());
        for (ToolInvocation value : values) {
            rows.add(new ToolInvocationAdminRow(value, displayToolName(value), summarize(value.getInputJson()),
                    summarize(value.getOutputText())));
        }
        return rows;
    }

    private String displayToolName(ToolInvocation value) {
        if (value.getSkillName() != null) return value.getSkillName();
        if (isShellCommand(value)) {
            String commandName = commandToolNameResolver.resolveInputJson(value.getInputJson());
            if (commandName != null) return commandName;
        }
        return value.getToolName();
    }

    private boolean isShellCommand(ToolInvocation value) {
        return value.getInvocationKind() == ToolInvocationKind.COMMAND_EXECUTION
                || (value.getProvider() == AgentType.CLAUDE
                && value.getInvocationKind() == ToolInvocationKind.TOOL_USE
                && "Bash".equals(value.getToolName()));
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
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
