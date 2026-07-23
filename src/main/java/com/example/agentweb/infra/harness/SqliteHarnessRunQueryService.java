package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.HarnessRunQueryService;
import com.example.agentweb.app.harness.HarnessRunView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Harness 管理详情的 SQLite CQRS 投影，不返回聚合或 ORM/JDBC 类型。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Repository
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class SqliteHarnessRunQueryService implements HarnessRunQueryService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public SqliteHarnessRunQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<HarnessRunView> findById(String runId) {
        List<HarnessRunView> views = jdbc.query(
                "SELECT id, title, working_dir, agent_type, environment, definition_version, "
                        + "status, created_by, created_at, updated_at, version "
                        + "FROM harness_run WHERE id=?",
                (rs, rowNumber) -> new HarnessRunView(
                        rs.getString("id"), rs.getString("title"), rs.getString("working_dir"),
                        rs.getString("agent_type"), rs.getString("environment"),
                        rs.getString("definition_version"), rs.getString("status"),
                        rs.getString("created_by"), rs.getLong("created_at"),
                        rs.getLong("updated_at"), rs.getLong("version"),
                        stages(runId), artifacts(runId), gates(runId), approvals(runId), events(runId)),
                runId);
        return views.isEmpty() ? Optional.<HarnessRunView>empty() : Optional.of(views.get(0));
    }

    private List<HarnessRunView.StageView> stages(String runId) {
        Map<String, List<HarnessRunView.AttemptView>> attempts = attempts(runId);
        Map<String, String> baselineHashes = lastRequestedBaselineHashes(runId);
        return jdbc.query("SELECT stage, status, required_outputs_json, gates_json, approval_type "
                        + "FROM harness_stage_execution WHERE run_id=? ORDER BY stage_order",
                (rs, rowNumber) -> {
                    String stage = rs.getString("stage");
                    return new HarnessRunView.StageView(
                            stage, rs.getString("status"),
                            stringList(rs.getString("required_outputs_json")),
                            stringList(rs.getString("gates_json")), rs.getString("approval_type"),
                            baselineHashes.get(stage), attempts.getOrDefault(
                                    stage, Collections.<HarnessRunView.AttemptView>emptyList()));
                }, runId);
    }

    private Map<String, List<HarnessRunView.AttemptView>> attempts(String runId) {
        Map<String, List<HarnessRunView.AttemptView>> grouped =
                new HashMap<String, List<HarnessRunView.AttemptView>>();
        jdbc.query("SELECT stage, attempt_number, status, started_at, finished_at, failure_reason "
                        + "FROM harness_stage_attempt WHERE run_id=? ORDER BY stage, attempt_number",
                (RowCallbackHandler) rs -> grouped.computeIfAbsent(rs.getString("stage"),
                                ignored -> new ArrayList<HarnessRunView.AttemptView>())
                        .add(new HarnessRunView.AttemptView(
                                rs.getInt("attempt_number"), rs.getString("status"),
                                rs.getLong("started_at"), nullableLong(rs, "finished_at"),
                                rs.getString("failure_reason"))), runId);
        return grouped;
    }

    private Map<String, String> lastRequestedBaselineHashes(String runId) {
        Map<String, String> hashes = new HashMap<String, String>();
        jdbc.query("SELECT stage, detail FROM harness_event WHERE run_id=? "
                        + "AND event_type='APPROVAL_REQUESTED' ORDER BY sequence",
                (RowCallbackHandler) rs -> hashes.put(
                        rs.getString("stage"), rs.getString("detail")), runId);
        return hashes;
    }

    private List<HarnessRunView.ArtifactView> artifacts(String runId) {
        return jdbc.query("SELECT artifact_id, artifact_type, version, stage, attempt_number, "
                        + "content_type, size_bytes, sha256, classification, created_by, created_at, "
                        + "source_artifacts_json FROM harness_artifact WHERE run_id=? "
                        + "ORDER BY created_at, artifact_type, version",
                (rs, rowNumber) -> new HarnessRunView.ArtifactView(
                        rs.getString("artifact_id"), rs.getString("artifact_type"),
                        rs.getInt("version"), rs.getString("stage"),
                        rs.getInt("attempt_number"), rs.getString("content_type"),
                        rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("classification"), rs.getString("created_by"),
                        rs.getLong("created_at"), rs.getString("source_artifacts_json")), runId);
    }

    private List<HarnessRunView.GateView> gates(String runId) {
        return jdbc.query("SELECT result_id, stage, attempt_number, rule, passed, "
                        + "artifact_baseline_hash, evidence_json, reason, evaluated_at "
                        + "FROM harness_gate_result WHERE run_id=? ORDER BY evaluated_at, result_id",
                (rs, rowNumber) -> new HarnessRunView.GateView(
                        rs.getString("result_id"), rs.getString("stage"),
                        rs.getInt("attempt_number"), rs.getString("rule"),
                        rs.getInt("passed") != 0, rs.getString("artifact_baseline_hash"),
                        rs.getString("evidence_json"), rs.getString("reason"),
                        rs.getLong("evaluated_at")), runId);
    }

    private List<HarnessRunView.ApprovalView> approvals(String runId) {
        return jdbc.query("SELECT approval_id, stage, attempt_number, approval_type, decision, "
                        + "artifact_baseline_hash, decided_by, reason, decided_at, valid, invalidated_at "
                        + "FROM harness_approval WHERE run_id=? ORDER BY decided_at, approval_id",
                (rs, rowNumber) -> new HarnessRunView.ApprovalView(
                        rs.getString("approval_id"), rs.getString("stage"),
                        rs.getInt("attempt_number"), rs.getString("approval_type"),
                        rs.getString("decision"), rs.getString("artifact_baseline_hash"),
                        rs.getString("decided_by"), rs.getString("reason"),
                        rs.getLong("decided_at"), rs.getInt("valid") != 0,
                        nullableLong(rs, "invalidated_at")), runId);
    }

    private List<HarnessRunView.EventView> events(String runId) {
        return jdbc.query("SELECT sequence, event_type, stage, actor, detail, occurred_at "
                        + "FROM harness_event WHERE run_id=? ORDER BY sequence",
                (rs, rowNumber) -> new HarnessRunView.EventView(
                        rs.getLong("sequence"), rs.getString("event_type"),
                        rs.getString("stage"), rs.getString("actor"),
                        rs.getString("detail"), rs.getLong("occurred_at")), runId);
    }

    private List<String> stringList(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<String> values = new ArrayList<String>();
            for (JsonNode node : root) {
                values.add(node.asText());
            }
            return values;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("invalid harness query projection json", ex);
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Long.valueOf(value);
    }
}
