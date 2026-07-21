package com.example.agentweb.domain.schedule;

import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a scheduled task that periodically executes a prompt.
 * @author zhourui(V33215020)
 */
public class ScheduledTask {
    @Getter
    private final String id;
    @Getter @Setter
    private String name;
    @Getter @Setter
    private String cronExpr;
    @Getter @Setter
    private String prompt;
    @Getter @Setter
    private String workingDir;
    @Getter @Setter
    private boolean enabled;
    @Getter
    private Instant createdAt;
    @Getter @Setter
    private Instant updatedAt;
    @Getter @Setter
    private Instant lastRunAt;
    @Getter @Setter
    private String lastSessionId;
    /** 创建者工号; 用于按用户隔离任务列表, 并在执行时透传到对话会话。NULL=老数据/系统。 */
    @Getter @Setter
    private String userId;

    public ScheduledTask(String name, String cronExpr, String prompt, String workingDir) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.cronExpr = cronExpr;
        this.prompt = prompt;
        this.workingDir = workingDir;
        this.enabled = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public ScheduledTask(String id, String name, String cronExpr, String prompt,
                         String workingDir, boolean enabled,
                         Instant createdAt, Instant updatedAt, Instant lastRunAt,
                         String lastSessionId, String userId) {
        this.id = id;
        this.name = name;
        this.cronExpr = cronExpr;
        this.prompt = prompt;
        this.workingDir = workingDir;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastRunAt = lastRunAt;
        this.lastSessionId = lastSessionId;
        this.userId = userId;
    }
}
