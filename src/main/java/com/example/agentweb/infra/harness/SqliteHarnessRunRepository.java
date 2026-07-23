package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.Approval;
import com.example.agentweb.domain.harness.ApprovalDecision;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.DuplicateHarnessRunException;
import com.example.agentweb.domain.harness.GateResult;
import com.example.agentweb.domain.harness.HarnessEvent;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessRunStatus;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.StageAttempt;
import com.example.agentweb.domain.harness.StageAttemptStatus;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.StageExecution;
import com.example.agentweb.domain.harness.StageStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * HarnessRun 完整聚合的 SQLite 写侧 Repository。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Repository
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class SqliteHarnessRunRepository implements HarnessRunRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RUN_COLUMNS = "id, title, working_dir, agent_type, environment, "
            + "definition_version, created_by, idempotency_key, status, created_at, updated_at, version";

    private final JdbcTemplate jdbc;

    public SqliteHarnessRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void add(HarnessRun run) {
        try {
            jdbc.update("INSERT INTO harness_run (" + RUN_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    run.getId(), run.getTitle(), run.getWorkingDir(), run.getAgentType(),
                    run.getEnvironment(), run.getDefinitionVersion(), run.getCreatedBy(),
                    run.getIdempotencyKey(), run.getStatus().name(), toMillis(run.getCreatedAt()),
                    toMillis(run.getUpdatedAt()), run.getVersion());
            insertChildren(run);
        } catch (DataAccessException ex) {
            throw translateInsertFailure(run, ex);
        }
    }

    @Override
    @Transactional
    public void update(HarnessRun run) {
        long expectedVersion = run.getVersion();
        int rows = jdbc.update("UPDATE harness_run SET title=?, working_dir=?, agent_type=?, "
                        + "environment=?, definition_version=?, status=?, updated_at=?, version=version+1 "
                        + "WHERE id=? AND version=?",
                run.getTitle(), run.getWorkingDir(), run.getAgentType(), run.getEnvironment(),
                run.getDefinitionVersion(), run.getStatus().name(), toMillis(run.getUpdatedAt()),
                run.getId(), expectedVersion);
        if (rows != 1) {
            throw new IllegalStateException("stale or missing harness run: " + run.getId());
        }
        deleteChildren(run.getId());
        insertChildren(run);
        run.synchronizeVersion(expectedVersion + 1L);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HarnessRun> findById(String runId) {
        List<RunRow> rows = jdbc.query("SELECT " + RUN_COLUMNS + " FROM harness_run WHERE id=?",
                this::mapRunRow, runId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        RunRow row = rows.get(0);
        return Optional.of(HarnessRun.restore(
                row.id, row.title, row.workingDir, row.agentType, row.environment,
                row.definitionVersion, row.createdBy, row.idempotencyKey, row.status,
                row.createdAt, row.updatedAt, row.version, loadStages(row.id),
                loadArtifacts(row.id), loadGateResults(row.id), loadApprovals(row.id),
                loadEvents(row.id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HarnessRun> findByCreatorAndIdempotencyKey(String createdBy,
                                                               String idempotencyKey) {
        List<String> ids = jdbc.queryForList(
                "SELECT id FROM harness_run WHERE created_by=? AND idempotency_key=?",
                String.class, createdBy, idempotencyKey);
        return ids.isEmpty() ? Optional.<HarnessRun>empty() : findById(ids.get(0));
    }

    private void insertChildren(HarnessRun run) {
        for (StageExecution execution : run.getStages()) {
            StageContract contract = execution.getContract();
            jdbc.update("INSERT INTO harness_stage_execution "
                            + "(run_id, stage, stage_order, status, required_inputs_json, "
                            + "required_outputs_json, gates_json, approval_type) VALUES (?,?,?,?,?,?,?,?)",
                    run.getId(), execution.getStage().name(), execution.getStage().ordinal(),
                    execution.getStatus().name(), writeEnumNames(contract.getRequiredInputArtifacts()),
                    writeEnumNames(contract.getRequiredOutputArtifacts()),
                    writeJson(contract.getDeterministicGates()), contract.getApprovalType());
            for (StageAttempt attempt : execution.getAttempts()) {
                jdbc.update("INSERT INTO harness_stage_attempt "
                                + "(run_id, stage, attempt_number, idempotency_key, status, started_at, "
                                + "finished_at, failure_reason) VALUES (?,?,?,?,?,?,?,?)",
                        run.getId(), execution.getStage().name(), attempt.getNumber(),
                        attempt.getIdempotencyKey(), attempt.getStatus().name(),
                        toMillis(attempt.getStartedAt()), toMillis(attempt.getFinishedAt()),
                        attempt.getFailureReason());
            }
        }
        for (ArtifactDescriptor artifact : run.getArtifacts()) {
            jdbc.update("INSERT INTO harness_artifact "
                            + "(run_id, artifact_id, artifact_type, version, stage, attempt_number, "
                            + "content_type, size_bytes, sha256, classification, created_by, created_at, "
                            + "source_artifacts_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    run.getId(), artifact.getArtifactId(), artifact.getArtifactType().name(),
                    artifact.getVersion(), artifact.getStage().name(), artifact.getAttempt(),
                    artifact.getContentType(), artifact.getSizeBytes(), artifact.getSha256(),
                    artifact.getClassification().name(), artifact.getCreatedBy(),
                    toMillis(artifact.getCreatedAt()), writeJson(artifact.getSourceArtifacts()));
        }
        for (GateResult result : run.getGateResults()) {
            jdbc.update("INSERT INTO harness_gate_result "
                            + "(result_id, run_id, stage, attempt_number, rule, passed, "
                            + "artifact_baseline_hash, evidence_json, reason, evaluated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    result.getResultId(), run.getId(), result.getStage().name(), result.getAttempt(),
                    result.getRule(), result.isPassed() ? 1 : 0,
                    result.getArtifactBaselineHash(), writeJson(result.getEvidenceReferences()),
                    result.getReason(), toMillis(result.getEvaluatedAt()));
        }
        for (Approval approval : run.getApprovals()) {
            jdbc.update("INSERT INTO harness_approval "
                            + "(approval_id, run_id, stage, attempt_number, approval_type, decision, "
                            + "artifact_baseline_hash, decided_by, reason, decided_at, valid, invalidated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    approval.getApprovalId(), run.getId(), approval.getStage().name(),
                    approval.getAttempt(), approval.getApprovalType(), approval.getDecision().name(),
                    approval.getArtifactBaselineHash(), approval.getDecidedBy(), approval.getReason(),
                    toMillis(approval.getDecidedAt()), approval.isValid() ? 1 : 0,
                    toMillis(approval.getInvalidatedAt()));
        }
        for (HarnessEvent event : run.getEvents()) {
            jdbc.update("INSERT INTO harness_event "
                            + "(run_id, sequence, event_type, stage, actor, detail, occurred_at) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    run.getId(), event.getSequence(), event.getEventType(),
                    event.getStage() == null ? null : event.getStage().name(),
                    event.getActor(), event.getDetail(), toMillis(event.getOccurredAt()));
        }
    }

    private void deleteChildren(String runId) {
        jdbc.update("DELETE FROM harness_event WHERE run_id=?", runId);
        jdbc.update("DELETE FROM harness_approval WHERE run_id=?", runId);
        jdbc.update("DELETE FROM harness_gate_result WHERE run_id=?", runId);
        jdbc.update("DELETE FROM harness_artifact WHERE run_id=?", runId);
        jdbc.update("DELETE FROM harness_stage_attempt WHERE run_id=?", runId);
        jdbc.update("DELETE FROM harness_stage_execution WHERE run_id=?", runId);
    }

    private List<StageExecution> loadStages(String runId) {
        Map<HarnessStage, List<StageAttempt>> attempts = loadAttempts(runId);
        return jdbc.query("SELECT stage, status, required_inputs_json, required_outputs_json, "
                        + "gates_json, approval_type FROM harness_stage_execution "
                        + "WHERE run_id=? ORDER BY stage_order",
                (rs, rowNumber) -> {
                    HarnessStage stage = HarnessStage.valueOf(rs.getString("stage"));
                    StageContract contract = new StageContract(stage,
                            readArtifactTypes(rs.getString("required_inputs_json")),
                            readArtifactTypes(rs.getString("required_outputs_json")),
                            new LinkedHashSet<String>(readStringList(rs.getString("gates_json"))),
                            rs.getString("approval_type"));
                    return StageExecution.restore(contract,
                            StageStatus.valueOf(rs.getString("status")),
                            attempts.getOrDefault(stage, Collections.<StageAttempt>emptyList()));
                }, runId);
    }

    private Map<HarnessStage, List<StageAttempt>> loadAttempts(String runId) {
        Map<HarnessStage, List<StageAttempt>> grouped = new EnumMap<HarnessStage, List<StageAttempt>>(
                HarnessStage.class);
        jdbc.query("SELECT stage, attempt_number, idempotency_key, status, started_at, "
                        + "finished_at, failure_reason FROM harness_stage_attempt "
                        + "WHERE run_id=? ORDER BY stage, attempt_number",
                rs -> {
                    HarnessStage stage = HarnessStage.valueOf(rs.getString("stage"));
                    grouped.computeIfAbsent(stage, ignored -> new ArrayList<StageAttempt>())
                            .add(StageAttempt.restore(rs.getInt("attempt_number"),
                                    rs.getString("idempotency_key"), fromMillis(rs, "started_at"),
                                    StageAttemptStatus.valueOf(rs.getString("status")),
                                    fromMillis(rs, "finished_at"), rs.getString("failure_reason")));
                }, runId);
        return grouped;
    }

    private List<ArtifactDescriptor> loadArtifacts(String runId) {
        return jdbc.query("SELECT artifact_id, artifact_type, version, stage, attempt_number, "
                        + "content_type, size_bytes, sha256, classification, created_by, created_at, "
                        + "source_artifacts_json FROM harness_artifact WHERE run_id=? "
                        + "ORDER BY created_at, artifact_type, version",
                (rs, rowNumber) -> new ArtifactDescriptor(
                        rs.getString("artifact_id"),
                        ArtifactType.valueOf(rs.getString("artifact_type")),
                        rs.getInt("version"), runId,
                        HarnessStage.valueOf(rs.getString("stage")),
                        rs.getInt("attempt_number"), rs.getString("content_type"),
                        rs.getLong("size_bytes"), rs.getString("sha256"),
                        ArtifactClassification.valueOf(rs.getString("classification")),
                        rs.getString("created_by"), fromMillis(rs, "created_at"),
                        readArtifactReferences(rs.getString("source_artifacts_json"))), runId);
    }

    private List<GateResult> loadGateResults(String runId) {
        return jdbc.query("SELECT result_id, stage, attempt_number, rule, passed, "
                        + "artifact_baseline_hash, evidence_json, reason, evaluated_at "
                        + "FROM harness_gate_result WHERE run_id=? ORDER BY evaluated_at, result_id",
                (rs, rowNumber) -> new GateResult(
                        rs.getString("result_id"), HarnessStage.valueOf(rs.getString("stage")),
                        rs.getInt("attempt_number"), rs.getString("rule"),
                        rs.getInt("passed") != 0, rs.getString("artifact_baseline_hash"),
                        readStringList(rs.getString("evidence_json")), rs.getString("reason"),
                        fromMillis(rs, "evaluated_at")), runId);
    }

    private List<Approval> loadApprovals(String runId) {
        return jdbc.query("SELECT approval_id, stage, attempt_number, approval_type, decision, "
                        + "artifact_baseline_hash, decided_by, reason, decided_at, valid, invalidated_at "
                        + "FROM harness_approval WHERE run_id=? ORDER BY decided_at, approval_id",
                (rs, rowNumber) -> new Approval(
                        rs.getString("approval_id"), HarnessStage.valueOf(rs.getString("stage")),
                        rs.getInt("attempt_number"), rs.getString("approval_type"),
                        ApprovalDecision.valueOf(rs.getString("decision")),
                        rs.getString("artifact_baseline_hash"), rs.getString("decided_by"),
                        rs.getString("reason"), fromMillis(rs, "decided_at"),
                        rs.getInt("valid") != 0, fromMillis(rs, "invalidated_at")), runId);
    }

    private List<HarnessEvent> loadEvents(String runId) {
        return jdbc.query("SELECT sequence, event_type, stage, actor, detail, occurred_at "
                        + "FROM harness_event WHERE run_id=? ORDER BY sequence",
                (rs, rowNumber) -> new HarnessEvent(
                        rs.getLong("sequence"), rs.getString("event_type"),
                        rs.getString("stage") == null ? null
                                : HarnessStage.valueOf(rs.getString("stage")),
                        rs.getString("actor"), rs.getString("detail"),
                        fromMillis(rs, "occurred_at")), runId);
    }

    private RunRow mapRunRow(ResultSet rs, int rowNumber) throws SQLException {
        return new RunRow(rs.getString("id"), rs.getString("title"),
                rs.getString("working_dir"), rs.getString("agent_type"),
                rs.getString("environment"), rs.getString("definition_version"),
                rs.getString("created_by"), rs.getString("idempotency_key"),
                HarnessRunStatus.valueOf(rs.getString("status")),
                fromMillis(rs, "created_at"), fromMillis(rs, "updated_at"),
                rs.getLong("version"));
    }

    private Set<ArtifactType> readArtifactTypes(String json) {
        List<String> names = readStringList(json);
        if (names.isEmpty()) {
            return Collections.emptySet();
        }
        Set<ArtifactType> types = EnumSet.noneOf(ArtifactType.class);
        for (String name : names) {
            types.add(ArtifactType.valueOf(name));
        }
        return types;
    }

    private List<ArtifactReference> readArtifactReferences(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<ArtifactReference> references = new ArrayList<ArtifactReference>();
            for (JsonNode node : root) {
                references.add(new ArtifactReference(
                        node.path("artifactId").asText(), node.path("version").asInt(),
                        node.path("sha256").asText()));
            }
            return references;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("invalid artifact references json", ex);
        }
    }

    private List<String> readStringList(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<String> values = new ArrayList<String>();
            for (JsonNode node : root) {
                values.add(node.asText());
            }
            return values;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("invalid string list json", ex);
        }
    }

    private String writeEnumNames(Set<ArtifactType> values) {
        List<String> names = new ArrayList<String>();
        for (ArtifactType value : values) {
            names.add(value.name());
        }
        return writeJson(names);
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not serialize harness metadata", ex);
        }
    }

    private RuntimeException translateInsertFailure(HarnessRun run, DataAccessException ex) {
        String message = rootMessage(ex);
        if (message.contains("harness_run.created_by, harness_run.idempotency_key")) {
            return new DuplicateHarnessRunException(run.getCreatedBy(), run.getIdempotencyKey());
        }
        return ex;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "" : current.getMessage();
    }

    private Long toMillis(Instant value) {
        return value == null ? null : Long.valueOf(value.toEpochMilli());
    }

    private Instant fromMillis(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static final class RunRow {

        private final String id;
        private final String title;
        private final String workingDir;
        private final String agentType;
        private final String environment;
        private final String definitionVersion;
        private final String createdBy;
        private final String idempotencyKey;
        private final HarnessRunStatus status;
        private final Instant createdAt;
        private final Instant updatedAt;
        private final long version;

        private RunRow(String id, String title, String workingDir, String agentType,
                       String environment, String definitionVersion, String createdBy,
                       String idempotencyKey, HarnessRunStatus status, Instant createdAt,
                       Instant updatedAt, long version) {
            this.id = id;
            this.title = title;
            this.workingDir = workingDir;
            this.agentType = agentType;
            this.environment = environment;
            this.definitionVersion = definitionVersion;
            this.createdBy = createdBy;
            this.idempotencyKey = idempotencyKey;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.version = version;
        }
    }
}
