package com.example.agentweb.app;

import com.example.agentweb.domain.ScheduledTask;

import java.util.List;

public interface ScheduledTaskService {

    ScheduledTask create(String name, String cronExpr, String prompt, String agentType, String workingDir);

    ScheduledTask update(String id, String name, String cronExpr, String prompt, String agentType, String workingDir);

    void delete(String id);

    void toggleEnabled(String id);

    List<ScheduledTask> listAll();

    ScheduledTask getById(String id);

    /**
     * Execute a task immediately. Returns the created session ID.
     */
    String executeTask(String taskId);
}
