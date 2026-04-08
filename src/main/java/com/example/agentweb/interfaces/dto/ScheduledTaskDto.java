package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.ScheduledTask;

public class ScheduledTaskDto {
    private String id;
    private String name;
    private String cronExpr;
    private String prompt;
    private String workingDir;
    private boolean enabled;
    private String createdAt;
    private String updatedAt;
    private String lastRunAt;
    private String lastSessionId;

    /**
     * 从领域对象构造 DTO。
     *
     * @param task 定时任务领域对象
     * @return DTO
     */
    public static ScheduledTaskDto from(ScheduledTask task) {
        ScheduledTaskDto dto = new ScheduledTaskDto();
        dto.id = task.getId();
        dto.name = task.getName();
        dto.cronExpr = task.getCronExpr();
        dto.prompt = task.getPrompt();
        dto.workingDir = task.getWorkingDir();
        dto.enabled = task.isEnabled();
        dto.createdAt = task.getCreatedAt() != null ? task.getCreatedAt().toString() : null;
        dto.updatedAt = task.getUpdatedAt() != null ? task.getUpdatedAt().toString() : null;
        dto.lastRunAt = task.getLastRunAt() != null ? task.getLastRunAt().toString() : null;
        dto.lastSessionId = task.getLastSessionId();
        return dto;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(String lastRunAt) { this.lastRunAt = lastRunAt; }

    public String getLastSessionId() { return lastSessionId; }
    public void setLastSessionId(String lastSessionId) { this.lastSessionId = lastSessionId; }
}
