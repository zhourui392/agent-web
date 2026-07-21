package com.example.agentweb.infra.requirement;

import com.example.agentweb.domain.requirement.AgentPlan;
import com.example.agentweb.domain.requirement.Requirement;
import com.example.agentweb.domain.requirement.RequirementEvent;
import com.example.agentweb.domain.requirement.RequirementId;
import com.example.agentweb.domain.requirement.RequirementRepository;
import com.example.agentweb.domain.requirement.RequirementSource;
import com.example.agentweb.domain.requirement.RequirementStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * requirement 表 SQLite 实现。事件语义由聚合定义，此处只做序列化落库（对齐 SqliteTicketRepo）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Repository
public class SqliteRequirementRepo implements RequirementRepository {

    private static final String COLUMNS = "id, title, description, status, status_before_suspend, "
            + "source_type, source_ref, owner, participants_json, workspace_id, plan_json, "
            + "created_at, updated_at";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final RowMapper<Requirement> rowMapper = (rs, rowNum) -> mapRow(rs);

    public SqliteRequirementRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Requirement requirement) {
        jdbc.update("INSERT INTO requirement (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                requirement.getId().getValue(),
                requirement.getTitle(),
                requirement.getDescription(),
                requirement.getStatus().name(),
                name(requirement.getStatusBeforeSuspend()),
                requirement.getSource().name(),
                requirement.getSourceRef(),
                requirement.getOwner(),
                participantsJson(requirement),
                requirement.getWorkspaceId(),
                planJson(requirement.getPlan()),
                requirement.getCreatedAt().toEpochMilli(),
                requirement.getUpdatedAt().toEpochMilli());
        flushEvents(requirement);
    }

    @Override
    public void update(Requirement requirement) {
        jdbc.update("UPDATE requirement SET title=?, description=?, status=?, status_before_suspend=?, "
                        + "participants_json=?, workspace_id=?, plan_json=?, updated_at=? WHERE id=?",
                requirement.getTitle(),
                requirement.getDescription(),
                requirement.getStatus().name(),
                name(requirement.getStatusBeforeSuspend()),
                participantsJson(requirement),
                requirement.getWorkspaceId(),
                planJson(requirement.getPlan()),
                requirement.getUpdatedAt().toEpochMilli(),
                requirement.getId().getValue());
        flushEvents(requirement);
    }

    @Override
    public Requirement findById(String requirementId) {
        List<Requirement> found = jdbc.query("SELECT " + COLUMNS + " FROM requirement WHERE id=?",
                rowMapper, requirementId);
        return found.isEmpty() ? null : found.get(0);
    }

    private void flushEvents(Requirement requirement) {
        for (RequirementEvent event : requirement.pullEvents()) {
            jdbc.update("INSERT INTO requirement_event (requirement_id, event_type, actor, "
                            + "from_status, to_status, payload_json, created_at) VALUES (?,?,?,?,?,?,?)",
                    requirement.getId().getValue(),
                    event.getEventType(),
                    event.getActor(),
                    name(event.getFromStatus()),
                    name(event.getToStatus()),
                    payloadJson(event.getDetail()),
                    event.getOccurredAt().toEpochMilli());
        }
    }

    private static String name(RequirementStatus status) {
        return status == null ? null : status.name();
    }

    private static String participantsJson(Requirement requirement) {
        try {
            return MAPPER.writeValueAsString(requirement.getParticipants());
        } catch (Exception e) {
            throw new IllegalStateException("serialize participants failed", e);
        }
    }

    private static String planJson(AgentPlan plan) {
        if (plan == null) {
            return null;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("planText", plan.getPlanText());
        node.put("promptHash", plan.getPromptHash());
        node.put("sourceRunId", plan.getSourceRunId());
        node.put("attachedAt", plan.getAttachedAt() == null ? null : plan.getAttachedAt().toEpochMilli());
        return node.toString();
    }

    private static String payloadJson(String detail) {
        if (detail == null) {
            return null;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("detail", detail);
        return node.toString();
    }

    private static Requirement mapRow(ResultSet rs) throws SQLException {
        String beforeSuspend = rs.getString("status_before_suspend");
        return new Requirement(
                new RequirementId(rs.getString("id")),
                RequirementSource.valueOf(rs.getString("source_type")),
                rs.getString("source_ref"),
                rs.getString("title"),
                rs.getString("description"),
                RequirementStatus.valueOf(rs.getString("status")),
                beforeSuspend == null ? null : RequirementStatus.valueOf(beforeSuspend),
                parsePlan(rs.getString("plan_json")),
                rs.getString("owner"),
                parseParticipants(rs.getString("participants_json")),
                rs.getString("workspace_id"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
    }

    private static AgentPlan parsePlan(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode attachedAt = node.get("attachedAt");
            return new AgentPlan(
                    textOrNull(node, "planText"),
                    textOrNull(node, "promptHash"),
                    textOrNull(node, "sourceRunId"),
                    attachedAt == null || attachedAt.isNull() ? null
                            : Instant.ofEpochMilli(attachedAt.asLong()));
        } catch (Exception e) {
            throw new IllegalStateException("parse plan_json failed", e);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> parseParticipants(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("parse participants_json failed", e);
        }
    }
}
