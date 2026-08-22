package com.example.agentweb.infra.mode;

import com.example.agentweb.app.mode.HandoffTranscriptFacts;
import com.example.agentweb.app.mode.HandoffTranscriptQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交接转录事实查询：chat_message 投影 + chat_run_event 的 file_changed 语义事件。
 *
 * @author alex
 * @since 2026-08-20
 */
@Component
@Slf4j
public class SqliteHandoffTranscriptQueryService implements HandoffTranscriptQueryService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public SqliteHandoffTranscriptQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public HandoffTranscriptFacts loadTranscript(String sessionId, int tailLimit) {
        int limit = Math.max(1, tailLimit);
        String firstUserGoal = jdbc.query(
                "SELECT content FROM chat_message WHERE session_id = ? AND role = 'user' "
                        + "ORDER BY id ASC LIMIT 1",
                (rs, n) -> rs.getString(1), sessionId).stream().findFirst().orElse(null);
        List<HandoffTranscriptFacts.TranscriptMessage> tailMessages = jdbc.query(
                "SELECT role, content FROM ("
                        + "  SELECT id, role, content FROM chat_message WHERE session_id = ? "
                        + "  ORDER BY id DESC LIMIT ?) ORDER BY id ASC",
                (rs, n) -> new HandoffTranscriptFacts.TranscriptMessage(
                        rs.getString(1), rs.getString(2)),
                sessionId, limit);
        List<String> payloads = jdbc.queryForList(
                "SELECT e.payload FROM chat_run_event e "
                        + "JOIN chat_run r ON r.id = e.run_id "
                        + "WHERE r.session_id = ? AND e.event_type = 'file_changed' "
                        + "ORDER BY e.run_id ASC, e.seq ASC",
                String.class, sessionId);
        // 按 path 去重保留最新 changeType（同一文件多次修改只留最终状态）
        Map<String, HandoffTranscriptFacts.TranscriptFileChange> latest = new LinkedHashMap<>();
        for (String payload : payloads) {
            JsonNode node = parseQuietly(payload);
            if (node == null) {
                continue;
            }
            String path = textOrNull(node.get("path"));
            String changeType = textOrNull(node.get("changeType"));
            if (path == null || changeType == null) {
                continue;
            }
            latest.put(path, new HandoffTranscriptFacts.TranscriptFileChange(path, changeType));
        }
        return new HandoffTranscriptFacts(firstUserGoal, tailMessages,
                new ArrayList<>(latest.values()));
    }

    private static JsonNode parseQuietly(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(payload);
        } catch (Exception ex) {
            log.debug("handoff-file-changed-payload-unreadable: {}", ex.getMessage());
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }
}
