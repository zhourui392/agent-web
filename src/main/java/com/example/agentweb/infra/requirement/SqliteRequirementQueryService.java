package com.example.agentweb.infra.requirement;

import com.example.agentweb.app.requirement.RequirementBoardItem;
import com.example.agentweb.app.requirement.RequirementDetail;
import com.example.agentweb.app.requirement.RequirementEventSearchItem;
import com.example.agentweb.app.requirement.RequirementEventView;
import com.example.agentweb.app.requirement.RequirementQueryService;
import com.example.agentweb.domain.requirement.RequirementStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 需求读侧 SQLite 投影（纯 SELECT 返回 DTO，SQL 内无业务判断——
 * 终态集合由 {@link RequirementStatus#isTerminal()} 域规则给出）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Service
public class SqliteRequirementQueryService implements RequirementQueryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> TERMINAL_STATUSES = Arrays.stream(RequirementStatus.values())
            .filter(RequirementStatus::isTerminal)
            .map(Enum::name)
            .collect(Collectors.toList());

    private final JdbcTemplate jdbc;

    public SqliteRequirementQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RequirementBoardItem> listBoard(String status, String owner) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, title, status, owner, updated_at, plan_json, workspace_id FROM requirement WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            sql.append(" AND status=?");
            params.add(status);
        }
        if (owner != null && !owner.isEmpty()) {
            sql.append(" AND owner=?");
            params.add(owner);
        }
        sql.append(" ORDER BY updated_at DESC");
        return jdbc.query(sql.toString(), (rs, rowNum) -> new RequirementBoardItem(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("owner"),
                rs.getLong("updated_at"),
                rs.getString("plan_json") != null,
                rs.getString("workspace_id") != null), params.toArray());
    }

    @Override
    public RequirementDetail getDetail(String requirementId) {
        List<RequirementDetail> found = jdbc.query(
                "SELECT * FROM requirement WHERE id=?", (rs, rowNum) -> mapDetail(rs), requirementId);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public List<RequirementEventView> listEvents(String requirementId) {
        return jdbc.query("SELECT id, event_type, actor, from_status, to_status, payload_json, created_at "
                        + "FROM requirement_event WHERE requirement_id=? ORDER BY id",
                (rs, rowNum) -> new RequirementEventView(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("actor"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        detailOf(rs.getString("payload_json")),
                        rs.getLong("created_at")), requirementId);
    }

    @Override
    public int countActiveByOwner(String owner) {
        String placeholders = TERMINAL_STATUSES.stream().map(s -> "?").collect(Collectors.joining(","));
        List<Object> params = new ArrayList<>();
        params.add(owner);
        params.addAll(TERMINAL_STATUSES);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM requirement WHERE owner=? AND status NOT IN (" + placeholders + ")",
                Integer.class, params.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public List<RequirementEventSearchItem> searchEvents(
            String actor, Long fromMillis, Long toMillis, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, requirement_id, event_type, actor, from_status, to_status, payload_json, "
                        + "created_at FROM requirement_event WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor=?");
            params.add(actor.trim());
        }
        if (fromMillis != null) {
            sql.append(" AND created_at>=?");
            params.add(fromMillis);
        }
        if (toMillis != null) {
            sql.append(" AND created_at<=?");
            params.add(toMillis);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(limit);
        return jdbc.query(sql.toString(),
                (rs, rowNum) -> new RequirementEventSearchItem(
                        rs.getLong("id"),
                        rs.getString("requirement_id"),
                        rs.getString("event_type"),
                        rs.getString("actor"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        detailOf(rs.getString("payload_json")),
                        rs.getLong("created_at")), params.toArray());
    }

    private RequirementDetail mapDetail(ResultSet rs) throws SQLException {
        String planJson = rs.getString("plan_json");
        String planText = null;
        Long planAttachedAt = null;
        if (planJson != null && !planJson.isEmpty()) {
            try {
                JsonNode node = MAPPER.readTree(planJson);
                planText = node.path("planText").isNull() ? null : node.path("planText").asText();
                planAttachedAt = node.path("attachedAt").isNull() ? null : node.path("attachedAt").asLong();
            } catch (Exception e) {
                throw new IllegalStateException("parse plan_json failed", e);
            }
        }
        return new RequirementDetail(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("status_before_suspend"),
                rs.getString("source_type"),
                rs.getString("source_ref"),
                rs.getString("owner"),
                parseParticipants(rs.getString("participants_json")),
                rs.getString("workspace_id"),
                planText,
                planAttachedAt,
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private List<String> parseParticipants(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("parse participants_json failed", e);
        }
    }

    private String detailOf(String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) {
            return null;
        }
        try {
            JsonNode detail = MAPPER.readTree(payloadJson).get("detail");
            return detail == null || detail.isNull() ? null : detail.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
