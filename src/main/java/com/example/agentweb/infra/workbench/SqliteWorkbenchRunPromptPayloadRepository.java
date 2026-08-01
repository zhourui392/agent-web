package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayloadRepository;
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
 * Workbench Run 私有最终 Prompt 的 SQLite Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteWorkbenchRunPromptPayloadRepository
        implements WorkbenchRunPromptPayloadRepository {

    private static final String COLUMNS =
            "run_id, final_prompt, prompt_hash, history_delivery, created_at";

    private final JdbcTemplate jdbc;

    public SqliteWorkbenchRunPromptPayloadRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional
    public void add(WorkbenchRunPromptPayload payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            jdbc.update("INSERT INTO workbench_run_prompt_payload ("
                            + COLUMNS + ") VALUES (?,?,?,?,?)",
                    payload.getRunId(), payload.getFinalPrompt(),
                    payload.getPromptHash(),
                    payload.getHistoryDelivery().name(),
                    payload.getCreatedAt().toEpochMilli());
        } catch (DataAccessException failure) {
            throw new IllegalStateException(
                    "workbench run prompt payload could not be added: "
                            + payload.getRunId(), failure);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkbenchRunPromptPayload> findByRunId(String runId) {
        String id = DomainText.require(
                runId, "workbench prompt run id", 128);
        List<PromptRow> rows = jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM workbench_run_prompt_payload WHERE run_id=?",
                this::read, id);
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
                    "corrupt workbench run prompt payload: " + id,
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

    private static final class PromptRow {
        private final String runId;
        private final String finalPrompt;
        private final String promptHash;
        private final String historyDelivery;
        private final Instant createdAt;

        private PromptRow(
                String runId, String finalPrompt, String promptHash,
                String historyDelivery, Instant createdAt) {
            this.runId = runId;
            this.finalPrompt = finalPrompt;
            this.promptHash = promptHash;
            this.historyDelivery = historyDelivery;
            this.createdAt = createdAt;
        }
    }
}
