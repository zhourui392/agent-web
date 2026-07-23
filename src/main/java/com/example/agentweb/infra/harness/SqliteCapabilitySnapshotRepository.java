package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.CapabilitySnapshotRepository;
import com.example.agentweb.domain.harness.HarnessStage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Capability Snapshot SQLite 写侧 Repository，数据库唯一键保证 Attempt 不可覆盖。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Repository
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class SqliteCapabilitySnapshotRepository implements CapabilitySnapshotRepository {

    private static final String COLUMNS = "run_id, stage, attempt_number, runtime, environment, "
            + "policy_version, prompt_pack_id, prompt_pack_version, prompt_pack_hash, "
            + "prompt_resource_hashes_json, selected_skills_json, rejected_skills_json, "
            + "capability_decisions_json, prompt_parts_json, final_prompt, prompt_hash, "
            + "snapshot_hash, created_at, schema_version, selected_mcp_servers_json, "
            + "rejected_mcp_servers_json, runtime_enforcement_json, "
            + "workspace_runtime_inventory_json";

    private final JdbcTemplate jdbc;

    public SqliteCapabilitySnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CapabilitySnapshot> find(String runId, HarnessStage stage, int attemptNumber) {
        List<CapabilitySnapshot> snapshots = jdbc.query(
                "SELECT " + COLUMNS + " FROM harness_capability_snapshot "
                        + "WHERE run_id=? AND stage=? AND attempt_number=?",
                (rs, rowNumber) -> CapabilitySnapshotJdbcCodec.read(rs),
                runId, stage.name(), attemptNumber);
        return snapshots.stream().findFirst();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CapabilitySnapshot saveIfAbsent(CapabilitySnapshot snapshot) {
        int inserted = jdbc.update("INSERT OR IGNORE INTO harness_capability_snapshot ("
                        + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                snapshot.getRunId(), snapshot.getStage().name(), snapshot.getAttemptNumber(),
                snapshot.getRuntime().name(), snapshot.getEnvironment(), snapshot.getPolicyVersion(),
                snapshot.getPromptPackId(), snapshot.getPromptPackVersion(), snapshot.getPromptPackHash(),
                CapabilitySnapshotJdbcCodec.json(snapshot.getPromptResourceHashes()),
                CapabilitySnapshotJdbcCodec.json(snapshot.getSelectedSkills()),
                CapabilitySnapshotJdbcCodec.json(snapshot.getRejectedSkills()),
                CapabilitySnapshotJdbcCodec.json(snapshot.getCapabilityDecisions()),
                CapabilitySnapshotJdbcCodec.json(snapshot.getPromptParts()), snapshot.getFinalPrompt(),
                snapshot.getPromptHash(), snapshot.getSnapshotHash(), snapshot.getCreatedAt().toEpochMilli(),
                snapshot.getSchemaVersion(),
                CapabilitySnapshotJdbcCodec.json(snapshot.getSelectedMcpServers()),
                CapabilitySnapshotJdbcCodec.json(snapshot.getRejectedMcpServers()),
                CapabilitySnapshotJdbcCodec.json(snapshot.getRuntimeEnforcementProfile()),
                CapabilitySnapshotJdbcCodec.json(snapshot.getWorkspaceRuntimeInventory()));
        if (inserted == 1) {
            return snapshot;
        }
        return find(snapshot.getRunId(), snapshot.getStage(), snapshot.getAttemptNumber())
                .orElseThrow(() -> new IllegalStateException("capability snapshot insert was ignored without row"));
    }
}
