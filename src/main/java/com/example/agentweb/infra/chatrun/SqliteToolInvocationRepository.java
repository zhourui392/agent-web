package com.example.agentweb.infra.chatrun;

import com.example.agentweb.domain.chatrun.ToolInvocation;
import com.example.agentweb.domain.chatrun.ToolInvocationKind;
import com.example.agentweb.domain.chatrun.ToolInvocationRepository;
import com.example.agentweb.domain.chatrun.ToolInvocationSource;
import com.example.agentweb.domain.chatrun.ToolInvocationStatus;
import com.example.agentweb.domain.chatrun.ToolInvocationTriggerSource;
import com.example.agentweb.domain.shared.AgentType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class SqliteToolInvocationRepository implements ToolInvocationRepository {

    private final JdbcTemplate jdbc;
    private final RowMapper<ToolInvocation> rowMapper = new InvocationRowMapper();
    private final SqliteTransientLockRetry lockRetry;

    public SqliteToolInvocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.lockRetry = new SqliteTransientLockRetry();
    }

    @Override
    public void save(ToolInvocation value) {
        value.validate();
        lockRetry.execute(() -> jdbc.update(
                "INSERT INTO chat_tool_invocation (session_id,run_id,assistant_message_id,provider,"
                        + "provider_call_id,invocation_index,invocation_kind,tool_name,skill_name,trigger_source,"
                        + "input_json,output_text,status,is_error,exit_code,provider_item_type,provider_status,"
                        + "input_truncated,output_truncated,output_original_size,started_at,completed_at,created_at,"
                        + "updated_at,source,source_message_id,migration_confidence) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,"
                        + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO UPDATE SET assistant_message_id=excluded.assistant_message_id,"
                        + "input_json=excluded.input_json,output_text=excluded.output_text,status=excluded.status,"
                        + "is_error=excluded.is_error,exit_code=excluded.exit_code,provider_status=excluded.provider_status,"
                        + "input_truncated=excluded.input_truncated,output_truncated=excluded.output_truncated,"
                        + "output_original_size=excluded.output_original_size,completed_at=excluded.completed_at,"
                        + "updated_at=excluded.updated_at,skill_name=excluded.skill_name",
                value.getSessionId(), value.getRunId(), value.getAssistantMessageId(), value.getProvider().name(),
                value.getProviderCallId(), value.getInvocationIndex(), value.getInvocationKind().name(),
                value.getToolName(), value.getSkillName(), value.getTriggerSource().name(), value.getInputJson(),
                value.getOutputText(), value.getStatus().name(), value.isError() ? 1 : 0, value.getExitCode(),
                value.getProviderItemType(), value.getProviderStatus(), value.isInputTruncated() ? 1 : 0,
                value.isOutputTruncated() ? 1 : 0, value.getOutputOriginalSize(), value.getStartedAt(),
                value.getCompletedAt(), value.getCreatedAt(), value.getUpdatedAt(), value.getSource().name(),
                value.getSourceMessageId(), value.getMigrationConfidence()));
    }

    @Override
    public void attachAssistantMessage(String runId, long assistantMessageId) {
        lockRetry.execute(() -> jdbc.update(
                "UPDATE chat_tool_invocation SET assistant_message_id=?, updated_at=? WHERE run_id=?",
                assistantMessageId, System.currentTimeMillis(), runId));
    }

    @Override
    public void completeExplicitSkills(String runId, ToolInvocationStatus status) {
        long now = System.currentTimeMillis();
        lockRetry.execute(() -> jdbc.update(
                "UPDATE chat_tool_invocation SET status=?,is_error=?,completed_at=?,updated_at=? "
                        + "WHERE run_id=? AND trigger_source='USER_SLASH' AND status='STARTED'",
                status.name(), status == ToolInvocationStatus.FAILED ? 1 : 0, now, now, runId));
    }

    @Override
    public List<ToolInvocation> findBySessionId(String sessionId, int limit, int offset) {
        return jdbc.query("SELECT * FROM chat_tool_invocation WHERE session_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",
                rowMapper, sessionId, limit, offset);
    }

    @Override
    public List<ToolInvocation> findByRunId(String runId, int limit, int offset) {
        return jdbc.query("SELECT * FROM chat_tool_invocation WHERE run_id=? ORDER BY invocation_index,id LIMIT ? OFFSET ?",
                rowMapper, runId, limit, offset);
    }

    static final class InvocationRowMapper implements RowMapper<ToolInvocation> {
        @Override
        public ToolInvocation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return ToolInvocation.builder().id(rs.getLong("id")).sessionId(rs.getString("session_id"))
                    .runId(rs.getString("run_id")).assistantMessageId(nullableLong(rs, "assistant_message_id"))
                    .provider(AgentType.valueOf(rs.getString("provider"))).providerCallId(rs.getString("provider_call_id"))
                    .invocationIndex(rs.getInt("invocation_index"))
                    .invocationKind(ToolInvocationKind.valueOf(rs.getString("invocation_kind")))
                    .toolName(rs.getString("tool_name")).skillName(rs.getString("skill_name"))
                    .triggerSource(ToolInvocationTriggerSource.valueOf(rs.getString("trigger_source")))
                    .inputJson(rs.getString("input_json")).outputText(rs.getString("output_text"))
                    .status(ToolInvocationStatus.valueOf(rs.getString("status"))).error(rs.getInt("is_error") != 0)
                    .exitCode(nullableInt(rs, "exit_code")).providerItemType(rs.getString("provider_item_type"))
                    .providerStatus(rs.getString("provider_status")).inputTruncated(rs.getInt("input_truncated") != 0)
                    .outputTruncated(rs.getInt("output_truncated") != 0)
                    .outputOriginalSize(nullableInt(rs, "output_original_size"))
                    .startedAt(nullableLong(rs, "started_at")).completedAt(nullableLong(rs, "completed_at"))
                    .createdAt(rs.getLong("created_at")).updatedAt(rs.getLong("updated_at"))
                    .source(ToolInvocationSource.valueOf(rs.getString("source")))
                    .sourceMessageId(nullableLong(rs, "source_message_id"))
                    .migrationConfidence(rs.getString("migration_confidence")).build();
        }

        private static Long nullableLong(ResultSet rs, String column) throws SQLException {
            long value = rs.getLong(column);
            return rs.wasNull() ? null : value;
        }

        private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
            int value = rs.getInt(column);
            return rs.wasNull() ? null : value;
        }
    }
}
