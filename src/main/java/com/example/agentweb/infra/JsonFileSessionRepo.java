package com.example.agentweb.infra;

import com.example.agentweb.domain.ChatSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Repository that persists chat sessions to JSON files.
 * Each session is stored in a separate file named {sessionId}.json
 */
@Repository
public class JsonFileSessionRepo {
    private static final String SESSIONS_DIR = "sessions";
    private final ObjectMapper mapper;

    public JsonFileSessionRepo() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ensureSessionsDir();
    }

    public void save(ChatSession session) {
        try {
            File file = getSessionFile(session.getId());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, session);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save session: " + session.getId(), e);
        }
    }

    public ChatSession find(String sessionId) {
        try {
            File file = getSessionFile(sessionId);
            if (!file.exists()) {
                return null;
            }
            return mapper.readValue(file, ChatSession.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load session: " + sessionId, e);
        }
    }

    private File getSessionFile(String sessionId) {
        return new File(SESSIONS_DIR, sessionId + ".json");
    }

    private void ensureSessionsDir() {
        try {
            Path path = Paths.get(SESSIONS_DIR);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create sessions directory", e);
        }
    }
}
