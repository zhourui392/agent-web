package com.example.agentweb;

import com.example.agentweb.domain.AgentType;
import com.example.agentweb.domain.ChatMessage;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.SessionRepository;
import com.example.agentweb.infra.InMemorySessionRepo;
import com.example.agentweb.infra.SqliteSessionRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TDD tests for "resume session from history" feature.
 *
 * Tests cover:
 * 1. ChatSession domain: resumeId field
 * 2. SqliteSessionRepo: updateResumeId + resumeId in queries
 * 3. ChatAppServiceImpl: getSession fallback to persistent storage
 * 4. API: history list returns resumeId; resumed session can send messages
 */
@SpringBootTest(properties = {
        "agent.fs.roots=/tmp",
        "agent.cli.codex.exec=/bin/echo",
        "agent.cli.codex.stdin=false",
        "agent.cli.codex.args=Echo,${MESSAGE}"
})
@AutoConfigureMockMvc
public class ResumeSessionTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private InMemorySessionRepo inMemoryRepo;

    // ── 1. Domain: ChatSession.resumeId ──

    @Test
    public void chatSession_should_have_resumeId_field() {
        ChatSession s = new ChatSession(AgentType.CLAUDE, "/tmp");
        assertNull(s.getResumeId(), "New session resumeId should be null");

        s.setResumeId("cli-session-abc");
        assertEquals("cli-session-abc", s.getResumeId());
    }

    // ── 2. Repository: persist and query resumeId ──

    @Test
    public void updateResumeId_should_persist() {
        ChatSession s = new ChatSession(AgentType.CLAUDE, "/tmp");
        sessionRepository.saveSession(s);

        sessionRepository.updateResumeId(s.getId(), "cli-resume-123");

        ChatSession loaded = sessionRepository.findById(s.getId());
        assertEquals("cli-resume-123", loaded.getResumeId());
    }

    @Test
    public void findSummaryPaged_should_include_resumeId() {
        ChatSession s = new ChatSession(AgentType.CLAUDE, "/tmp");
        sessionRepository.saveSession(s);
        sessionRepository.updateResumeId(s.getId(), "cli-resume-456");

        List<Map<String, Object>> summaries = sessionRepository.findSummaryPaged(0, 100);
        Map<String, Object> found = summaries.stream()
                .filter(m -> s.getId().equals(m.get("sessionId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Session not found in summaries"));

        assertEquals("cli-resume-456", found.get("resumeId"));
    }

    @Test
    public void findAllSummary_should_include_resumeId() {
        ChatSession s = new ChatSession(AgentType.CLAUDE, "/tmp");
        sessionRepository.saveSession(s);
        sessionRepository.updateResumeId(s.getId(), "cli-resume-789");

        List<Map<String, Object>> summaries = sessionRepository.findAllSummary();
        Map<String, Object> found = summaries.stream()
                .filter(m -> s.getId().equals(m.get("sessionId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Session not found in summaries"));

        assertEquals("cli-resume-789", found.get("resumeId"));
    }

    // ── 3. Service: getSession fallback to persistent storage ──

    @Test
    public void getSession_should_fallback_to_persistent_repo() throws Exception {
        // Create session via API (puts it in both repos)
        Path tmp = Files.createTempDirectory("agent-web-test");
        String body = "{\"agentType\": \"CODEX\", \"workingDir\": \"" + tmp.toString().replace("\\", "\\\\") + "\"}";
        String resp = mvc.perform(post("/api/chat/session")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sid = resp.replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1");

        // Remove from in-memory repo to simulate server restart
        inMemoryRepo.remove(sid);
        assertNull(inMemoryRepo.find(sid), "In-memory should be empty after remove");

        // Sending a message should still work (service falls back to DB)
        mvc.perform(post("/api/chat/session/" + sid + "/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello after restart\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output", containsString("Echo")));
    }

    // ── 4. API: history list returns resumeId ──

    @Test
    public void sessions_api_should_return_resumeId() throws Exception {
        ChatSession s = new ChatSession(AgentType.CLAUDE, "/tmp");
        sessionRepository.saveSession(s);
        sessionRepository.updateResumeId(s.getId(), "cli-api-test");
        sessionRepository.addMessage(s.getId(), new ChatMessage("user", "hi"));

        mvc.perform(get("/api/chat/sessions?page=1&size=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.sessionId=='" + s.getId() + "')].resumeId",
                        hasItem("cli-api-test")));
    }
}
