package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.run.WorkbenchPhaseHistoryQuery;
import com.example.agentweb.domain.workbench.PhaseConversationProvisioning;
import com.example.agentweb.domain.workbench.WorkbenchPhaseHistory;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 从当前 Phase Session 读取私有消息历史的 SQLite 投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class SqliteWorkbenchPhaseHistoryQuery
        implements WorkbenchPhaseHistoryQuery {

    private final JdbcTemplate jdbc;

    public SqliteWorkbenchPhaseHistoryQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WorkbenchPhaseHistory load(
            PhaseConversationProvisioning provisioning) {
        if (provisioning == null) {
            throw new IllegalArgumentException(
                    "phase conversation provisioning must not be null");
        }
        String sessionId = provisioning.requireCurrentConversationId();
        List<String> messages = jdbc.query(
                "SELECT role, content FROM chat_message "
                        + "WHERE session_id=? ORDER BY id",
                (resultSet, rowNumber) -> resultSet.getString("role")
                        + ": " + resultSet.getString("content"),
                sessionId);
        return WorkbenchPhaseHistory.freeze(
                sessionId, provisioning.getContextId(),
                String.join("\n\n", messages),
                WorkbenchPromptHistoryDelivery.PROMPT_PREFIX);
    }
}
