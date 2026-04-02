package com.example.agentweb.interfaces;

import com.example.agentweb.app.ScheduledTaskService;
import com.example.agentweb.domain.ScheduledTask;
import com.example.agentweb.interfaces.dto.CreateScheduledTaskRequest;
import com.example.agentweb.interfaces.dto.UpdateScheduledTaskRequest;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
@RequestMapping(path = "/api/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class ScheduledTaskController {

    private final ScheduledTaskService taskService;
    private final Executor agentExecutor;

    public ScheduledTaskController(ScheduledTaskService taskService, Executor agentExecutor) {
        this.taskService = taskService;
        this.agentExecutor = agentExecutor;
    }

    @GetMapping
    public List<Map<String, Object>> listTasks() {
        List<ScheduledTask> tasks = taskService.listAll();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ScheduledTask t : tasks) {
            result.add(toMap(t));
        }
        return result;
    }

    @PostMapping
    public Map<String, Object> createTask(@Valid @RequestBody CreateScheduledTaskRequest req) {
        ScheduledTask task = taskService.create(
                req.getName(), req.getCronExpr(), req.getPrompt(),
                req.getAgentType(), req.getWorkingDir());
        return toMap(task);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getTask(@PathVariable String id) {
        ScheduledTask task = taskService.getById(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        return toMap(task);
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateTask(@PathVariable String id, @RequestBody UpdateScheduledTaskRequest req) {
        ScheduledTask task = taskService.update(
                id, req.getName(), req.getCronExpr(), req.getPrompt(),
                req.getAgentType(), req.getWorkingDir());
        return toMap(task);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteTask(@PathVariable String id) {
        taskService.delete(id);
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("success", true);
        return result;
    }

    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggleTask(@PathVariable String id) {
        taskService.toggleEnabled(id);
        ScheduledTask task = taskService.getById(id);
        return toMap(task);
    }

    @PostMapping("/{id}/run")
    public Map<String, Object> runTask(@PathVariable String id) {
        // Run asynchronously to avoid blocking the HTTP thread
        agentExecutor.execute(new Runnable() {
            @Override
            public void run() {
                taskService.executeTask(id);
            }
        });
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("success", true);
        result.put("message", "任务已触发");
        return result;
    }

    private Map<String, Object> toMap(ScheduledTask t) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("cronExpr", t.getCronExpr());
        m.put("prompt", t.getPrompt());
        m.put("agentType", t.getAgentType().name());
        m.put("workingDir", t.getWorkingDir());
        m.put("enabled", t.isEnabled());
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        m.put("updatedAt", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
        m.put("lastRunAt", t.getLastRunAt() != null ? t.getLastRunAt().toString() : null);
        m.put("lastSessionId", t.getLastSessionId());
        return m;
    }
}
