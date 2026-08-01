package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.capability.port.ActiveRunCapabilityBindingQuery;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 从 Phase 当前活动 Run 的不可变 Snapshot 查询 Capability Binding Hash。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteActiveRunCapabilityBindingQuery
        implements ActiveRunCapabilityBindingQuery {

    private final JdbcTemplate jdbc;
    private final WorkbenchJsonCodec codec;

    public SqliteActiveRunCapabilityBindingQuery(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.codec = new WorkbenchJsonCodec();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findActiveBindingHash(
            WorkbenchId workbenchId, WorkbenchPhase phase) {
        Objects.requireNonNull(workbenchId, "workbenchId");
        Objects.requireNonNull(phase, "phase");
        List<BindingRow> rows = jdbc.query(
                "SELECT s.capability_bindings_json, "
                        + "s.capability_snapshot_hash "
                        + "FROM workbench_phase p "
                        + "JOIN workbench_run_snapshot s "
                        + "ON s.run_id=p.active_run_id "
                        + "AND s.workbench_id=p.workbench_id "
                        + "AND s.phase=p.phase "
                        + "WHERE p.workbench_id=? AND p.phase=? "
                        + "AND p.active_run_id IS NOT NULL",
                (resultSet, rowNumber) -> new BindingRow(
                        resultSet.getString("capability_bindings_json"),
                        resultSet.getString("capability_snapshot_hash")),
                workbenchId.getValue(), phase.name());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BindingRow row = rows.get(0);
        try {
            ResolvedCapabilityBinding binding =
                    codec.readCapabilityBinding(row.bindingJson);
            if (!binding.getBindingHash().equals(row.storedHash)) {
                throw new IllegalArgumentException(
                        "stored capability hash does not match binding");
            }
            return Optional.of(binding.getBindingHash());
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "corrupt active run capability binding for workbench phase",
                    failure);
        }
    }

    private static final class BindingRow {
        private final String bindingJson;
        private final String storedHash;

        private BindingRow(String bindingJson, String storedHash) {
            this.bindingJson = bindingJson;
            this.storedHash = storedHash;
        }
    }
}
