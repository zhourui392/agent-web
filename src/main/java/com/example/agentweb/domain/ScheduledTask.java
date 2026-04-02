package com.example.agentweb.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a scheduled task that periodically executes a prompt.
 */
public class ScheduledTask {
    private final String id;
    private String name;
    private String cronExpr;
    private String prompt;
    private AgentType agentType;
    private String workingDir;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastRunAt;
    private String lastSessionId;

    public ScheduledTask(String name, String cronExpr, String prompt,
                         AgentType agentType, String workingDir) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.cronExpr = cronExpr;
        this.prompt = prompt;
        this.agentType = agentType;
        this.workingDir = workingDir;
        this.enabled = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public ScheduledTask(String id, String name, String cronExpr, String prompt,
                         AgentType agentType, String workingDir, boolean enabled,
                         Instant createdAt, Instant updatedAt, Instant lastRunAt,
                         String lastSessionId) {
        this.id = id;
        this.name = name;
        this.cronExpr = cronExpr;
        this.prompt = prompt;
        this.agentType = agentType;
        this.workingDir = workingDir;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastRunAt = lastRunAt;
        this.lastSessionId = lastSessionId;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public AgentType getAgentType() { return agentType; }
    public void setAgentType(AgentType agentType) { this.agentType = agentType; }
    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
    public String getLastSessionId() { return lastSessionId; }
    public void setLastSessionId(String lastSessionId) { this.lastSessionId = lastSessionId; }
}
