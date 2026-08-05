package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.run.WorkbenchStageHistoryQuery;
import com.example.agentweb.domain.chat.SessionKind;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchStageConversationHistory;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageConversationProvisioning;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 从严格绑定的当前 Dynamic Stage Session 读取私有消息历史。
 *
 * @author alex
 * @since 2026-08-05
 */
@Component
@Transactional(readOnly = true)
public class SqliteWorkbenchStageHistoryQuery
        implements WorkbenchStageHistoryQuery {

    private static final String CURRENT_STAGE_HISTORY_SQL =
            "SELECT c.session_id, m.role, m.content "
                    + "FROM workbench w "
                    + "JOIN workbench_stage s ON s.workbench_id = w.id "
                    + "JOIN workbench_stage_conversation c "
                    + "ON c.workbench_id = s.workbench_id "
                    + "AND c.stage_instance_identifier = "
                    + "s.stage_instance_identifier "
                    + "JOIN workbench_repository_scope scope "
                    + "ON scope.workbench_id = w.id "
                    + "AND scope.primary_repository = 1 "
                    + "JOIN chat_session cs ON cs.id = c.session_id "
                    + "LEFT JOIN chat_message m ON m.session_id = c.session_id "
                    + "WHERE w.id = ? AND w.version = ? "
                    + "AND w.owner_id = ? AND w.owner_name = ? "
                    + "AND w.agent_type = ? AND w.environment IS ? "
                    + "AND w.status = 'ACTIVE' "
                    + "AND s.stage_instance_identifier = ? "
                    + "AND s.status IN ('NOT_STARTED', 'IN_PROGRESS') "
                    + "AND s.conversation_generation = ? "
                    + "AND c.generation = ? AND c.session_id = ? "
                    + "AND c.created_by_id = ? AND c.created_by_name = ? "
                    + "AND c.created_at = ? AND c.retired_at IS NULL "
                    + "AND scope.repository_root = ? "
                    + "AND cs.id = ? AND cs.session_kind = ? "
                    + "AND cs.agent_type = ? AND cs.working_dir = ? "
                    + "AND cs.created_at = ? AND cs.env IS ? "
                    + "AND cs.context_id = ? AND cs.retired_at IS NULL "
                    + "AND cs.user_id = ? AND cs.user_name = ? "
                    + "ORDER BY m.id";

    private final JdbcTemplate jdbc;

    public SqliteWorkbenchStageHistoryQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WorkbenchStageConversationHistory load(
            WorkbenchStageConversationProvisioning provisioning) {
        if (provisioning == null) {
            throw new IllegalArgumentException(
                    "Stage conversation provisioning must not be null");
        }
        String sessionId = provisioning.requireCurrentConversationId();
        List<HistoryRow> rows = jdbc.query(
                CURRENT_STAGE_HISTORY_SQL,
                (resultSet, rowNumber) -> new HistoryRow(
                        resultSet.getString("session_id"),
                        resultSet.getString("role"),
                        resultSet.getString("content")),
                provisioning.getWorkbenchId().getValue(),
                provisioning.getWorkbenchVersion(),
                provisioning.getOwner().getOwnerId(),
                provisioning.getOwner().getOwnerName(),
                provisioning.getAgentType().name(),
                provisioning.getEnvironment(),
                provisioning.getStageInstanceIdentifier(),
                provisioning.getCurrentConversationGeneration(),
                provisioning.getCurrentConversationGeneration(),
                sessionId,
                provisioning.getOwner().getOwnerId(),
                provisioning.getOwner().getOwnerName(),
                provisioning.getCurrentConversationCreatedAt().toEpochMilli(),
                provisioning.getPrimaryRepositoryRoot(),
                sessionId,
                SessionKind.WORKBENCH_STAGE.name(),
                provisioning.getAgentType().name(),
                provisioning.getPrimaryRepositoryRoot(),
                provisioning.getCurrentConversationCreatedAt().toString(),
                provisioning.getEnvironment(),
                provisioning.getContextId(),
                provisioning.getOwner().getOwnerId(),
                provisioning.getOwner().getOwnerName());
        if (rows.isEmpty()) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        return WorkbenchStageConversationHistory.freeze(
                sessionId, provisioning.getContextId(),
                provisioning.getCurrentConversationGeneration(),
                String.join("\n\n", formattedMessages(rows)),
                WorkbenchPromptHistoryDelivery.PROMPT_PREFIX);
    }

    private List<String> formattedMessages(List<HistoryRow> rows) {
        List<String> messages = new ArrayList<String>();
        for (HistoryRow row : rows) {
            if (!row.sessionId.equals(rows.get(0).sessionId)) {
                throw WorkbenchDomainException.runBindingCorrupted();
            }
            if (row.role != null) {
                messages.add(row.role + ": " + row.content);
            }
        }
        return messages;
    }

    private static final class HistoryRow {

        private final String sessionId;
        private final String role;
        private final String content;

        private HistoryRow(String sessionId, String role, String content) {
            this.sessionId = sessionId;
            this.role = role;
            this.content = content;
        }
    }
}
