package com.example.agentweb.infra;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

@Component
public class SqliteInitializer {

    private final JdbcTemplate jdbc;

    public SqliteInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() throws Exception {
        String sql = StreamUtils.copyToString(
                new ClassPathResource("schema.sql").getInputStream(),
                StandardCharsets.UTF_8
        );
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbc.execute(trimmed);
            }
        }
        // Migration: add resume_id column for existing databases
        try {
            jdbc.execute("ALTER TABLE chat_session ADD COLUMN resume_id TEXT");
        } catch (Exception ignored) {
            // column already exists
        }
    }
}
