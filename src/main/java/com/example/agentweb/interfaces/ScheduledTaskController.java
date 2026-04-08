package com.example.agentweb.interfaces;

import com.example.agentweb.app.ScheduledTaskService;
import com.example.agentweb.domain.ScheduledTask;
import com.example.agentweb.interfaces.dto.CreateScheduledTaskRequest;
import com.example.agentweb.interfaces.dto.RunTaskResponse;
import com.example.agentweb.interfaces.dto.ScheduledTaskDto;
import com.example.agentweb.interfaces.dto.SuccessResponse;
import com.example.agentweb.interfaces.dto.UpdateScheduledTaskRequest;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.List;
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
    public List<ScheduledTaskDto> listTasks() {
        List<ScheduledTask> tasks = taskService.listAll();
        List<ScheduledTaskDto> result = new ArrayList<ScheduledTaskDto>();
        for (ScheduledTask t : tasks) {
            result.add(ScheduledTaskDto.from(t));
        }
        return result;
    }

    @PostMapping
    public ScheduledTaskDto createTask(@Valid @RequestBody CreateScheduledTaskRequest req) {
        ScheduledTask task = taskService.create(
                req.getName(), req.getCronExpr(), req.getPrompt(),
                req.getWorkingDir());
        return ScheduledTaskDto.from(task);
    }

    @GetMapping("/{id}")
    public ScheduledTaskDto getTask(@PathVariable String id) {
        ScheduledTask task = taskService.getById(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        return ScheduledTaskDto.from(task);
    }

    @PutMapping("/{id}")
    public ScheduledTaskDto updateTask(@PathVariable String id, @RequestBody UpdateScheduledTaskRequest req) {
        ScheduledTask task = taskService.update(
                id, req.getName(), req.getCronExpr(), req.getPrompt(),
                req.getWorkingDir());
        return ScheduledTaskDto.from(task);
    }

    @DeleteMapping("/{id}")
    public SuccessResponse deleteTask(@PathVariable String id) {
        taskService.delete(id);
        return new SuccessResponse(true);
    }

    @PostMapping("/{id}/toggle")
    public ScheduledTaskDto toggleTask(@PathVariable String id) {
        taskService.toggleEnabled(id);
        ScheduledTask task = taskService.getById(id);
        return ScheduledTaskDto.from(task);
    }

    @PostMapping("/{id}/run")
    public RunTaskResponse runTask(@PathVariable String id) {
        // Run asynchronously to avoid blocking the HTTP thread
        agentExecutor.execute(new Runnable() {
            @Override
            public void run() {
                taskService.executeTask(id);
            }
        });
        return new RunTaskResponse(true, "任务已触发");
    }
}
