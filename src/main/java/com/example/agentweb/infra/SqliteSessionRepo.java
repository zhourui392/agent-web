package com.example.agentweb.infra;

import com.example.agentweb.domain.AgentType;
import com.example.agentweb.domain.ChatMessage;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.SessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class SqliteSessionRepo implements SessionRepository {

    private final JdbcTemplate jdbc;

    public SqliteSessionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveSession(ChatSession session) {
        jdbc.update(
                "INSERT OR IGNORE INTO chat_session (id, agent_type, working_dir, created_at) VALUES (?, ?, ?, ?)",
                session.getId(),
                session.getAgentType().name(),
                session.getWorkingDir(),
                session.getCreatedAt().toString()
        );
    }

    @Override
    public void addMessage(String sessionId, ChatMessage message) {
        jdbc.update(
                "INSERT INTO chat_message (session_id, role, content, timestamp) VALUES (?, ?, ?, ?)",
                sessionId,
                message.getRole(),
                message.getContent(),
                message.getTimestamp().toString()
        );
    }

    @Override
    public ChatSession findById(String id) {
        List<ChatSession> sessions = jdbc.query(
                "SELECT id, agent_type, working_dir, created_at FROM chat_session WHERE id = ?",
                new Object[]{id},
                (rs, rowNum) -> new ChatSession(
                        rs.getString("id"),
                        AgentType.valueOf(rs.getString("agent_type")),
                        rs.getString("working_dir"),
                        Instant.parse(rs.getString("created_at")),
                        loadMessages(rs.getString("id"))
                )
        );
        return sessions.isEmpty() ? null : sessions.get(0);
    }

    @Override
    public List<ChatSession> findAll() {
        return jdbc.query(
                "SELECT id, agent_type, working_dir, created_at FROM chat_session ORDER BY created_at DESC",
                (rs, rowNum) -> new ChatSession(
                        rs.getString("id"),
                        AgentType.valueOf(rs.getString("agent_type")),
                        rs.getString("working_dir"),
                        Instant.parse(rs.getString("created_at")),
                        loadMessages(rs.getString("id"))
                )
        );
    }

    @Override
    public List<Map<String, Object>> findAllSummary() {
        return jdbc.query(
                "SELECT s.id, s.agent_type, s.working_dir, s.created_at, " +
                "  (SELECT COUNT(*) FROM chat_message m WHERE m.session_id = s.id) AS message_count, " +
                "  (SELECT m.content FROM chat_message m WHERE m.session_id = s.id AND m.role = 'user' ORDER BY m.id ASC LIMIT 1) AS title " +
                "FROM chat_session s ORDER BY s.created_at DESC",
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<String, Object>();
                    m.put("sessionId", rs.getString("id"));
                    m.put("agentType", rs.getString("agent_type"));
                    m.put("workingDir", rs.getString("working_dir"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("messageCount", rs.getInt("message_count"));
                    String title = rs.getString("title");
                    m.put("title", title != null ? (title.length() > 50 ? title.substring(0, 50) + "..." : title) : "新对话");
                    return m;
                }
        );
    }

    @Override
    public List<Map<String, Object>> findSummaryPaged(int offset, int limit) {
        return jdbc.query(
                "SELECT s.id, s.agent_type, s.working_dir, s.created_at, " +
                "  (SELECT COUNT(*) FROM chat_message m WHERE m.session_id = s.id) AS message_count, " +
                "  (SELECT m.content FROM chat_message m WHERE m.session_id = s.id AND m.role = 'user' ORDER BY m.id ASC LIMIT 1) AS title " +
                "FROM chat_session s ORDER BY s.created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<String, Object>();
                    m.put("sessionId", rs.getString("id"));
                    m.put("agentType", rs.getString("agent_type"));
                    m.put("workingDir", rs.getString("working_dir"));
                    m.put("createdAt", rs.getString("created_at"));
                    m.put("messageCount", rs.getInt("message_count"));
                    String title = rs.getString("title");
                    m.put("title", title != null ? (title.length() > 50 ? title.substring(0, 50) + "..." : title) : "新对话");
                    return m;
                },
                limit, offset
        );
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("DELETE FROM chat_message WHERE session_id = ?", id);
        jdbc.update("DELETE FROM chat_session WHERE id = ?", id);
    }

    private List<ChatMessage> loadMessages(String sessionId) {
        return jdbc.query(
                "SELECT role, content, timestamp FROM chat_message WHERE session_id = ? ORDER BY id ASC",
                new Object[]{sessionId},
                (rs, rowNum) -> new ChatMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        Instant.parse(rs.getString("timestamp"))
                )
        );
    }
}
