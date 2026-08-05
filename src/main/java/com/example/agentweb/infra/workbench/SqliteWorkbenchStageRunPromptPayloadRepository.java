package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchStageRunPromptPayloadRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Dynamic Stage Run 私有最终 Prompt 的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-05
 */
@Repository
public class SqliteWorkbenchStageRunPromptPayloadRepository
        implements WorkbenchStageRunPromptPayloadRepository {

    private static final String COLUMNS =
            "run_id, final_prompt, prompt_hash, history_delivery, created_at";

    private final JdbcTemplate jdbcTemplate;

    public SqliteWorkbenchStageRunPromptPayloadRepository(
            JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(
                jdbcTemplate, "jdbcTemplate");
    }

    @Override
    @Transactional
    public void add(WorkbenchRunPromptPayload payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            jdbcTemplate.update(
                    "INSERT INTO workbench_stage_run_prompt_payload ("
                            + COLUMNS + ") VALUES (?,?,?,?,?)",
                    payload.getRunId(), payload.getFinalPrompt(),
                    payload.getPromptHash(),
                    payload.getHistoryDelivery().name(),
                    payload.getCreatedAt().toEpochMilli());
        } catch (DataAccessException failure) {
            throw new IllegalStateException(
                    "Workbench Stage Run prompt payload could not be added: "
                            + payload.getRunId(),
                    failure);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkbenchRunPromptPayload> findByRunId(String runId) {
        String identifier = DomainText.require(
                runId, "Workbench Stage prompt Run identifier", 128);
        List<PromptRow> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS
                        + " FROM workbench_stage_run_prompt_payload "
                        + "WHERE run_id=?",
                this::read, identifier);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        PromptRow row = rows.get(0);
        try {
            return Optional.of(WorkbenchRunPromptPayload.restore(
                    row.runId, row.finalPrompt, row.promptHash,
                    WorkbenchPromptHistoryDelivery.valueOf(
                            row.historyDelivery),
                    row.createdAt));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Corrupt Workbench Stage Run prompt payload: "
                            + identifier,
                    failure);
        }
    }

    private PromptRow read(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new PromptRow(
                resultSet.getString("run_id"),
                resultSet.getString("final_prompt"),
                resultSet.getString("prompt_hash"),
                resultSet.getString("history_delivery"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")));
    }

    private record PromptRow(
            String runId, String finalPrompt, String promptHash,
            String historyDelivery, Instant createdAt) {
    }
}
