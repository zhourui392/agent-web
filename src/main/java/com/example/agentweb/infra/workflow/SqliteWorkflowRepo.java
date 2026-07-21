package com.example.agentweb.infra.workflow;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workflow.Workflow;
import com.example.agentweb.domain.workflow.WorkflowRepository;
import com.example.agentweb.domain.workflow.WorkflowStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link WorkflowRepository} 的 SQLite 实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Repository
public class SqliteWorkflowRepo implements WorkflowRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLUMNS =
            "id, name, description, agent_type, working_dir, steps_json, enabled, "
                    + "created_by, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public SqliteWorkflowRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Workflow workflow) {
        jdbc.update(
                "INSERT INTO workflow_definition (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)",
                workflow.getId(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.getAgentType().name(),
                workflow.getWorkingDir(),
                serializeSteps(workflow.getSteps()),
                workflow.isEnabled() ? 1 : 0,
                workflow.getCreatedBy(),
                toEpochMillis(workflow.getCreatedAt()),
                toEpochMillis(workflow.getUpdatedAt()));
    }

    @Override
    public void update(Workflow workflow) {
        jdbc.update(
                "UPDATE workflow_definition SET name=?, description=?, agent_type=?, working_dir=?, "
                        + "steps_json=?, enabled=?, updated_at=? WHERE id=?",
                workflow.getName(),
                workflow.getDescription(),
                workflow.getAgentType().name(),
                workflow.getWorkingDir(),
                serializeSteps(workflow.getSteps()),
                workflow.isEnabled() ? 1 : 0,
                toEpochMillis(workflow.getUpdatedAt()),
                workflow.getId());
    }

    @Override
    public Workflow findById(String id) {
        List<Workflow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM workflow_definition WHERE id=?",
                ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<Workflow> findAll() {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM workflow_definition ORDER BY created_at DESC",
                ROW_MAPPER);
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("DELETE FROM workflow_definition WHERE id=?", id);
    }

    private static String serializeSteps(List<WorkflowStep> steps) {
        ArrayNode array = MAPPER.createArrayNode();
        for (WorkflowStep step : steps) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("name", step.getName());
            node.put("promptTemplate", step.getPromptTemplate());
            node.put("timeoutSeconds", step.getTimeoutSeconds());
            array.add(node);
        }
        return array.toString();
    }

    private static List<WorkflowStep> deserializeSteps(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            List<WorkflowStep> steps = new ArrayList<>();
            if (node != null && node.isArray()) {
                for (JsonNode item : node) {
                    steps.add(new WorkflowStep(
                            item.path("name").asText(),
                            item.path("promptTemplate").asText(),
                            item.path("timeoutSeconds").asLong(0L)));
                }
            }
            return steps;
        } catch (Exception e) {
            throw new IllegalArgumentException("工作流步骤 JSON 解析失败", e);
        }
    }

    private static Long toEpochMillis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant fromEpochMillis(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static final RowMapper<Workflow> ROW_MAPPER = (rs, rowNum) -> new Workflow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("description"),
            AgentType.valueOf(rs.getString("agent_type")),
            rs.getString("working_dir"),
            deserializeSteps(rs.getString("steps_json")),
            rs.getInt("enabled") != 0,
            rs.getString("created_by"),
            fromEpochMillis(rs, "created_at"),
            fromEpochMillis(rs, "updated_at"));
}
