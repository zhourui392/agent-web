package com.example.agentweb.infra.nativeagent;

import com.example.agentweb.domain.diagnosis.DiagnosisCheckpoint;
import com.example.agentweb.domain.diagnosis.DiagnosisCheckpointRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SQLite persistence for message-boundary native diagnosis checkpoints.
 *
 * @author alex
 * @since 2026-07-29
 */
@Repository
public class SqliteDiagnosisCheckpointRepository implements DiagnosisCheckpointRepository {

    private static final String COLUMNS = "c.run_id, c.session_id, c.user_message_id, "
            + "c.assistant_message_id, c.state_snapshot, c.snapshot_schema_version, "
            + "c.input_tokens, c.output_tokens, c.cache_read_input_tokens, c.created_at";

    private final JdbcTemplate jdbc;

    public SqliteDiagnosisCheckpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(DiagnosisCheckpoint checkpoint) {
        jdbc.update("INSERT INTO native_diagnosis_checkpoint (run_id, session_id, "
                        + "user_message_id, assistant_message_id, state_snapshot, "
                        + "snapshot_schema_version, input_tokens, output_tokens, "
                        + "cache_read_input_tokens, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                checkpoint.getRunId(), checkpoint.getSessionId(), checkpoint.getUserMessageId(),
                checkpoint.getAssistantMessageId(), checkpoint.getStateSnapshot(),
                checkpoint.getSnapshotSchemaVersion(), checkpoint.getInputTokens(),
                checkpoint.getOutputTokens(), checkpoint.getCacheReadInputTokens(),
                checkpoint.getCreatedAt().toEpochMilli());
    }

    @Override
    public Optional<DiagnosisCheckpoint> findLatestValidBefore(String sessionId,
                                                               long currentUserMessageId) {
        List<DiagnosisCheckpoint> checkpoints = jdbc.query(
                "SELECT " + COLUMNS + " FROM native_diagnosis_checkpoint c "
                        + "JOIN chat_message u ON u.id = c.user_message_id "
                        + "AND u.session_id = c.session_id "
                        + "JOIN chat_message a ON a.id = c.assistant_message_id "
                        + "AND a.session_id = c.session_id "
                        + "WHERE c.session_id = ? AND c.user_message_id < ? "
                        + "AND c.assistant_message_id < ? "
                        + "ORDER BY c.assistant_message_id DESC LIMIT 1",
                (rs, rowNumber) -> map(rs), sessionId, currentUserMessageId,
                currentUserMessageId);
        return checkpoints.isEmpty() ? Optional.empty() : Optional.of(checkpoints.get(0));
    }

    @Override
    public void deleteCrossingBoundary(String sessionId, long fromMessageId) {
        jdbc.update("DELETE FROM native_diagnosis_checkpoint WHERE session_id = ? "
                        + "AND (user_message_id >= ? OR assistant_message_id >= ?)",
                sessionId, fromMessageId, fromMessageId);
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        jdbc.update("DELETE FROM native_diagnosis_checkpoint WHERE session_id = ?", sessionId);
    }

    private DiagnosisCheckpoint map(ResultSet rs) throws SQLException {
        return DiagnosisCheckpoint.record(rs.getString("run_id"), rs.getString("session_id"),
                rs.getLong("user_message_id"), rs.getLong("assistant_message_id"),
                rs.getString("state_snapshot"), rs.getString("snapshot_schema_version"),
                rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                rs.getLong("cache_read_input_tokens"),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }
}
