package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.ChatRunRuntimeSelectionStore;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.shared.AgentType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/** SQLite adapter for the Run-owned non-secret RuntimeSelection.
 *
 * @author alex
 * @since 2026-08-07
 */
public final class SqliteChatRunRuntimeSelectionStore
        implements ChatRunRuntimeSelectionStore {

    private final JdbcTemplate jdbc;

    public SqliteChatRunRuntimeSelectionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ChatRunId runId, RuntimeSelection selection) {
        int inserted = jdbc.update("INSERT OR IGNORE INTO chat_run_runtime_selection "
                        + "(run_id, profile_id, agent_type, endpoint, model, reasoning_effort, "
                        + "runtime_environment, version_policy, version_value) VALUES (?,?,?,?,?,?,?,?,?)",
                runId.getValue(), selection.getProfileId(), selection.getAgentType().name(),
                selection.getEndpoint(), selection.getModel(), selection.getReasoningEffort(),
                selection.getRuntimeEnvironment(), selection.getRuntimeVersionPolicy().getMode().name(),
                selection.getRuntimeVersionPolicy().exactVersion().orElse(""));
        if (inserted == 1) {
            return;
        }
        RuntimeSelection existing = find(runId).orElseThrow(() ->
                new IllegalStateException("RuntimeSelection row was not persisted"));
        if (!sameSelection(existing, selection)) {
            throw new IllegalStateException(
                    "RuntimeSelection is already frozen for Run: " + runId.getValue());
        }
    }

    @Override
    public Optional<RuntimeSelection> find(ChatRunId runId) {
        List<RuntimeSelection> values = jdbc.query(
                "SELECT profile_id, agent_type, endpoint, model, reasoning_effort, "
                        + "runtime_environment, version_policy, version_value "
                        + "FROM chat_run_runtime_selection WHERE run_id=?",
                (rs, rowNum) -> new RuntimeSelection(
                        rs.getString("profile_id"), AgentType.valueOf(rs.getString("agent_type")),
                        rs.getString("endpoint"), rs.getString("model"),
                        rs.getString("reasoning_effort"), rs.getString("runtime_environment"),
                        runtimeVersionPolicy(rs.getString("version_policy"), rs.getString("version_value"))),
                runId.getValue());
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    private RuntimeVersionPolicy runtimeVersionPolicy(String mode, String value) {
        if ("EXACT".equals(mode)) {
            return RuntimeVersionPolicy.exact(value);
        }
        return RuntimeVersionPolicy.configured();
    }

    private boolean sameSelection(RuntimeSelection left, RuntimeSelection right) {
        return java.util.Objects.equals(left.getProfileId(), right.getProfileId())
                && left.getAgentType() == right.getAgentType()
                && java.util.Objects.equals(left.getEndpoint(), right.getEndpoint())
                && java.util.Objects.equals(left.getModel(), right.getModel())
                && java.util.Objects.equals(left.getReasoningEffort(), right.getReasoningEffort())
                && java.util.Objects.equals(left.getRuntimeEnvironment(), right.getRuntimeEnvironment())
                && left.getRuntimeVersionPolicy().equals(right.getRuntimeVersionPolicy());
    }
}
