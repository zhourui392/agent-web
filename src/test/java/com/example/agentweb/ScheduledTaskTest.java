package com.example.agentweb;

import com.example.agentweb.domain.AgentType;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.ScheduledTask;
import com.example.agentweb.domain.ScheduledTaskRepository;
import com.example.agentweb.domain.SessionRepository;
import com.example.agentweb.infra.InMemorySessionRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * TDD tests for the scheduled task mechanism.
 *
 * Covers:
 * 1. Domain: ScheduledTask entity + ChatSession.forTask factory
 * 2. Repository: CRUD operations on scheduled_task table
 * 3. Session title: COALESCE logic for explicit vs derived titles
 * 4. API: CRUD endpoints for /api/tasks
 * 5. API: toggle enable/disable
 * 6. API: manual run trigger
 */
@SpringBootTest(properties = {
        "agent.fs.roots=/tmp",
        "agent.cli.codex.exec=/bin/echo",
        "agent.cli.codex.stdin=false",
        "agent.cli.codex.args=Echo,${MESSAGE}"
})
@AutoConfigureMockMvc
public class ScheduledTaskTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ScheduledTaskRepository taskRepo;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private InMemorySessionRepo inMemoryRepo;

    // ── 1. Domain: ScheduledTask entity ──

    @Test
    public void scheduledTask_should_generate_id_and_defaults() {
        ScheduledTask task = new ScheduledTask("daily-report", "0 0 9 * * ?", "生成日报", "/tmp");
        assertNotNull(task.getId());
        assertEquals("daily-report", task.getName());
        assertEquals("0 0 9 * * ?", task.getCronExpr());
        assertEquals("生成日报", task.getPrompt());
        assertEquals("/tmp", task.getWorkingDir());
        assertTrue(task.isEnabled());
        assertNotNull(task.getCreatedAt());
        assertNull(task.getLastRunAt());
        assertNull(task.getLastSessionId());
    }

    @Test
    public void chatSession_forTask_should_set_title_prefix() {
        ChatSession session = ChatSession.forTask("daily-report", AgentType.CLAUDE, "/tmp");
        assertEquals("Task-daily-report", session.getTitle());
        assertNotNull(session.getId());
        assertEquals(AgentType.CLAUDE, session.getAgentType());
        assertEquals("/tmp", session.getWorkingDir());
    }

    @Test
    public void chatSession_normal_should_have_null_title() {
        ChatSession session = new ChatSession(AgentType.CLAUDE, "/tmp");
        assertNull(session.getTitle());
    }

    // ── 2. Repository: persist and query ──

    @Test
    public void save_and_findById_should_round_trip() {
        ScheduledTask task = new ScheduledTask("test-save", "0 0 * * * ?", "hello", "/tmp");
        taskRepo.save(task);

        ScheduledTask loaded = taskRepo.findById(task.getId());
        assertNotNull(loaded);
        assertEquals(task.getId(), loaded.getId());
        assertEquals("test-save", loaded.getName());
        assertEquals("0 0 * * * ?", loaded.getCronExpr());
        assertEquals("hello", loaded.getPrompt());
        assertTrue(loaded.isEnabled());
    }

    @Test
    public void update_should_persist_changes() {
        ScheduledTask task = new ScheduledTask("test-update", "0 0 * * * ?", "original", "/tmp");
        taskRepo.save(task);

        task.setName("updated-name");
        task.setPrompt("updated prompt");
        task.setCronExpr("0 */30 * * * ?");
        taskRepo.update(task);

        ScheduledTask loaded = taskRepo.findById(task.getId());
        assertEquals("updated-name", loaded.getName());
        assertEquals("updated prompt", loaded.getPrompt());
        assertEquals("0 */30 * * * ?", loaded.getCronExpr());
    }

    @Test
    public void deleteById_should_remove_task() {
        ScheduledTask task = new ScheduledTask("test-delete", "0 0 * * * ?", "bye", "/tmp");
        taskRepo.save(task);
        assertNotNull(taskRepo.findById(task.getId()));

        taskRepo.deleteById(task.getId());
        assertNull(taskRepo.findById(task.getId()));
    }

    @Test
    public void findAllEnabled_should_filter_disabled() {
        ScheduledTask enabled = new ScheduledTask("enabled-task", "0 0 * * * ?", "e", "/tmp");
        taskRepo.save(enabled);

        ScheduledTask disabled = new ScheduledTask("disabled-task", "0 0 * * * ?", "d", "/tmp");
        disabled.setEnabled(false);
        // Need to save with enabled=false — save uses constructor default true,
        // so update right after to flip it
        taskRepo.save(disabled);
        taskRepo.update(disabled);

        List<ScheduledTask> result = taskRepo.findAllEnabled();
        assertTrue(result.stream().anyMatch(t -> t.getId().equals(enabled.getId())));
        assertFalse(result.stream().anyMatch(t -> t.getId().equals(disabled.getId())));
    }

    @Test
    public void updateLastRun_should_persist() {
        ScheduledTask task = new ScheduledTask("test-lastrun", "0 0 * * * ?", "run", "/tmp");
        taskRepo.save(task);

        Instant now = Instant.now();
        taskRepo.updateLastRun(task.getId(), now, "session-abc");

        ScheduledTask loaded = taskRepo.findById(task.getId());
        assertNotNull(loaded.getLastRunAt());
        assertEquals("session-abc", loaded.getLastSessionId());
    }

    // ── 3. Session title: COALESCE behavior ──

    @Test
    public void session_with_explicit_title_should_appear_in_summary() {
        ChatSession session = ChatSession.forTask("my-task", AgentType.CODEX, "/tmp");
        sessionRepository.saveSession(session);

        List<Map<String, Object>> summaries = sessionRepository.findSummaryPaged(0, 200);
        Map<String, Object> found = summaries.stream()
                .filter(m -> session.getId().equals(m.get("sessionId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Task session not found in summaries"));

        assertEquals("Task-my-task", found.get("title"));
    }

    @Test
    public void session_without_title_should_derive_from_first_message() {
        ChatSession session = new ChatSession(AgentType.CODEX, "/tmp");
        sessionRepository.saveSession(session);
        sessionRepository.addMessage(session.getId(),
                new com.example.agentweb.domain.ChatMessage("user", "这是首条消息"));

        List<Map<String, Object>> summaries = sessionRepository.findSummaryPaged(0, 200);
        Map<String, Object> found = summaries.stream()
                .filter(m -> session.getId().equals(m.get("sessionId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Session not found"));

        assertEquals("这是首条消息", found.get("title"));
    }

    @Test
    public void findById_should_load_title() {
        ChatSession session = ChatSession.forTask("load-title", AgentType.CLAUDE, "/tmp");
        sessionRepository.saveSession(session);

        ChatSession loaded = sessionRepository.findById(session.getId());
        assertEquals("Task-load-title", loaded.getTitle());
    }

    // ── 4. API: CRUD for /api/tasks ──

    @Test
    public void create_task_api_should_return_task() throws Exception {
        String body = "{\"name\":\"api-test\",\"cronExpr\":\"0 0 * * * ?\","
                + "\"prompt\":\"test prompt\",\"workingDir\":\"/tmp\"}";

        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("api-test"))
                .andExpect(jsonPath("$.cronExpr").value("0 0 * * * ?"))
                .andExpect(jsonPath("$.prompt").value("test prompt"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    public void create_task_with_invalid_cron_should_fail() throws Exception {
        String body = "{\"name\":\"bad-cron\",\"cronExpr\":\"not-a-cron\","
                + "\"prompt\":\"test\",\"workingDir\":\"/tmp\"}";

        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    public void list_tasks_api_should_return_all() throws Exception {
        // Create a task first
        String body = "{\"name\":\"list-test\",\"cronExpr\":\"0 0 9 * * ?\","
                + "\"prompt\":\"p\",\"workingDir\":\"/tmp\"}";
        mvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.name=='list-test')]").exists());
    }

    @Test
    public void update_task_api_should_modify_fields() throws Exception {
        // Create
        String createBody = "{\"name\":\"update-test\",\"cronExpr\":\"0 0 * * * ?\","
                + "\"prompt\":\"old\",\"workingDir\":\"/tmp\"}";
        MvcResult createResult = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andReturn();
        String id = createResult.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // Update
        String updateBody = "{\"name\":\"updated-test\",\"prompt\":\"new prompt\"}";
        mvc.perform(put("/api/tasks/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("updated-test"))
                .andExpect(jsonPath("$.prompt").value("new prompt"));
    }

    @Test
    public void delete_task_api_should_succeed() throws Exception {
        // Create
        String body = "{\"name\":\"delete-test\",\"cronExpr\":\"0 0 * * * ?\","
                + "\"prompt\":\"d\",\"workingDir\":\"/tmp\"}";
        MvcResult result = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String id = result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // Delete
        mvc.perform(delete("/api/tasks/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Verify gone
        assertNull(taskRepo.findById(id));
    }

    // ── 5. API: toggle enable/disable ──

    @Test
    public void toggle_task_should_flip_enabled() throws Exception {
        String body = "{\"name\":\"toggle-test\",\"cronExpr\":\"0 0 * * * ?\","
                + "\"prompt\":\"t\",\"workingDir\":\"/tmp\"}";
        MvcResult result = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String id = result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        // Should be enabled initially
        ScheduledTask task = taskRepo.findById(id);
        assertTrue(task.isEnabled());

        // Toggle -> disabled
        mvc.perform(post("/api/tasks/" + id + "/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // Toggle again -> enabled
        mvc.perform(post("/api/tasks/" + id + "/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    // ── 6. API: manual run trigger ──

    @Test
    public void run_task_should_return_success() throws Exception {
        String body = "{\"name\":\"run-test\",\"cronExpr\":\"0 0 * * * ?\","
                + "\"prompt\":\"hello from task\",\"workingDir\":\"/tmp\"}";
        MvcResult result = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        String id = result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(post("/api/tasks/" + id + "/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("任务已触发"));
    }
}
