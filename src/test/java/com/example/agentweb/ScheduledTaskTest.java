package com.example.agentweb;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.schedule.CronExpression;
import com.example.agentweb.domain.schedule.ScheduledTask;
import com.example.agentweb.domain.schedule.ScheduledTaskRepository;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.infra.InMemorySessionRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
        "agent.cli.codex.stdin=false"
})
@AutoConfigureMockMvc
@Tag("spring-flow")
@ResourceLock("spring-flow-sqlite")
public class ScheduledTaskTest {

    @DynamicPropertySource
    static void configureEchoCli(DynamicPropertyRegistry registry) {
        TestCliStub.register(registry);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ScheduledTaskRepository taskRepo;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private com.example.agentweb.app.ChatSessionQueryService sessionQueryService;

    @Autowired
    private InMemorySessionRepo inMemoryRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        // run_task_should_return_success 触发的异步任务执行(agentExecutor)可能仍在写库,
        // 与下面的 DELETE 在 shared-cache 内存库上撞表级锁(SQLITE_LOCKED_SHAREDCACHE,
        // busy_timeout 对该错误码无效)。重试退避以等在途写入落定 —— teardown 本就该等。
        deleteWithRetry("DELETE FROM chat_message");
        deleteWithRetry("DELETE FROM chat_session");
        deleteWithRetry("DELETE FROM scheduled_task");
    }

    private void deleteWithRetry(String sql) {
        DataAccessException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                jdbc.update(sql);
                return;
            } catch (DataAccessException e) {
                last = e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    // ── 1. Domain: ScheduledTask entity ──

    @Test
    public void scheduledTask_should_generate_id_and_defaults() {
        ScheduledTask task = task("daily-report", "0 0 9 * * ?", "生成日报", "/tmp");
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
        ScheduledTask task = task("test-save", "0 0 * * * ?", "hello", "/tmp");
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
        ScheduledTask task = task("test-update", "0 0 * * * ?", "original", "/tmp");
        taskRepo.save(task);

        task.revise("updated-name", "0 */30 * * * ?", "updated prompt", null, Instant.now());
        taskRepo.update(task);

        ScheduledTask loaded = taskRepo.findById(task.getId());
        assertEquals("updated-name", loaded.getName());
        assertEquals("updated prompt", loaded.getPrompt());
        assertEquals("0 */30 * * * ?", loaded.getCronExpr());
    }

    @Test
    public void deleteById_should_remove_task() {
        ScheduledTask task = task("test-delete", "0 0 * * * ?", "bye", "/tmp");
        taskRepo.save(task);
        assertNotNull(taskRepo.findById(task.getId()));

        taskRepo.deleteById(task.getId());
        assertNull(taskRepo.findById(task.getId()));
    }

    @Test
    public void findAllEnabled_should_filter_disabled() {
        ScheduledTask enabled = task("enabled-task", "0 0 * * * ?", "e", "/tmp");
        taskRepo.save(enabled);

        ScheduledTask disabled = task("disabled-task", "0 0 * * * ?", "d", "/tmp");
        disabled.toggle(Instant.now());
        taskRepo.save(disabled);

        List<ScheduledTask> result = taskRepo.findAllEnabled();
        assertTrue(result.stream().anyMatch(t -> t.getId().equals(enabled.getId())));
        assertFalse(result.stream().anyMatch(t -> t.getId().equals(disabled.getId())));
    }

    @Test
    public void updateLastRun_should_persist() {
        ScheduledTask task = task("test-lastrun", "0 0 * * * ?", "run", "/tmp");
        taskRepo.save(task);

        Instant now = Instant.now();
        task.recordRun("session-abc", now);
        taskRepo.update(task);

        ScheduledTask loaded = taskRepo.findById(task.getId());
        assertNotNull(loaded.getLastRunAt());
        assertEquals("session-abc", loaded.getLastSessionId());
    }

    // ── 3. Session title: COALESCE behavior ──

    @Test
    public void session_with_explicit_title_should_appear_in_summary() {
        ChatSession session = ChatSession.forTask("my-task", AgentType.CODEX, "/tmp");
        sessionRepository.saveSession(session);

        com.example.agentweb.app.ChatSessionSummary found = sessionQueryService.findSummaryPaged(0, 200).stream()
                .filter(m -> session.getId().equals(m.getSessionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Task session not found in summaries"));

        assertEquals("Task-my-task", found.getTitle());
    }

    @Test
    public void session_without_title_should_derive_from_first_message() {
        ChatSession session = new ChatSession(AgentType.CODEX, "/tmp");
        sessionRepository.saveSession(session);
        sessionRepository.addMessage(session.getId(),
                new com.example.agentweb.domain.chat.ChatMessage("user", "这是首条消息"));

        com.example.agentweb.app.ChatSessionSummary found = sessionQueryService.findSummaryPaged(0, 200).stream()
                .filter(m -> session.getId().equals(m.getSessionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Session not found"));

        assertEquals("这是首条消息", found.getTitle());
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

    private ScheduledTask task(String name, String cron, String prompt, String workingDir) {
        return ScheduledTask.create(name, CronExpression.parse(cron), prompt, workingDir,
                null, Instant.now());
    }
}
