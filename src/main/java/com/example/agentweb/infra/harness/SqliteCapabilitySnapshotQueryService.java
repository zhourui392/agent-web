package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.CapabilitySnapshotQueryService;
import com.example.agentweb.app.harness.CapabilitySnapshotView;
import com.example.agentweb.domain.harness.HarnessStage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Capability Snapshot 管理预览的 SQLite CQRS 读模型。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Service
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class SqliteCapabilitySnapshotQueryService implements CapabilitySnapshotQueryService {

    private final JdbcTemplate jdbc;

    public SqliteCapabilitySnapshotQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CapabilitySnapshotView> find(String runId, HarnessStage stage, int attemptNumber) {
        List<CapabilitySnapshotView> views = jdbc.query(
                "SELECT run_id, stage, attempt_number, runtime, environment, policy_version, "
                        + "prompt_pack_id, prompt_pack_version, prompt_pack_hash, "
                        + "prompt_resource_hashes_json, selected_skills_json, rejected_skills_json, "
                        + "capability_decisions_json, prompt_parts_json, final_prompt, prompt_hash, "
                        + "snapshot_hash, created_at, schema_version, selected_mcp_servers_json, "
                        + "rejected_mcp_servers_json, runtime_enforcement_json, "
                        + "workspace_runtime_inventory_json "
                        + "FROM harness_capability_snapshot "
                        + "WHERE run_id=? AND stage=? AND attempt_number=?",
                (rs, rowNumber) -> new CapabilitySnapshotView(CapabilitySnapshotJdbcCodec.read(rs)),
                runId, stage.name(), attemptNumber);
        return views.stream().findFirst();
    }
}
