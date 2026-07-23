package com.example.agentweb.domain.schedule;

import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 定时任务聚合根。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class ScheduledTask {

    private final String id;
    private String name;
    private CronExpression cronExpression;
    private String prompt;
    private String workingDir;
    private boolean enabled;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant lastRunAt;
    private String lastSessionId;
    private final String userId;

    private ScheduledTask(String id, String name, CronExpression cronExpression, String prompt,
                          String workingDir, boolean enabled, Instant createdAt, Instant updatedAt,
                          Instant lastRunAt, String lastSessionId, String userId) {
        this.id = requireText(id, "任务 ID 不能为空");
        this.name = requireText(name, "任务名称不能为空");
        this.cronExpression = Objects.requireNonNull(cronExpression, "Cron 表达式不能为空");
        this.prompt = requireText(prompt, "任务提示词不能为空");
        this.workingDir = requireText(workingDir, "工作目录不能为空");
        this.enabled = enabled;
        this.createdAt = Objects.requireNonNull(createdAt, "创建时间不能为空");
        this.updatedAt = Objects.requireNonNull(updatedAt, "更新时间不能为空");
        this.lastRunAt = lastRunAt;
        this.lastSessionId = lastSessionId;
        this.userId = normalizeNullable(userId);
    }

    /** 创建新任务并建立初始状态。 */
    public static ScheduledTask create(String name, CronExpression cronExpression, String prompt,
                                       String workingDir, String userId, Instant now) {
        return new ScheduledTask(UUID.randomUUID().toString(), name, cronExpression, prompt,
                workingDir, true, now, now, null, null, userId);
    }

    /** 从持久化状态恢复聚合。 */
    public static ScheduledTask restore(String id, String name, CronExpression cronExpression,
                                        String prompt, String workingDir, boolean enabled,
                                        Instant createdAt, Instant updatedAt, Instant lastRunAt,
                                        String lastSessionId, String userId) {
        return new ScheduledTask(id, name, cronExpression, prompt, workingDir, enabled,
                createdAt, updatedAt, lastRunAt, lastSessionId, userId);
    }

    public String getCronExpr() {
        return cronExpression.value();
    }

    public void rename(String newName, Instant changedAt) {
        this.name = requireText(newName, "任务名称不能为空");
        this.updatedAt = requireTime(changedAt);
    }

    public void reschedule(CronExpression newExpression, Instant changedAt) {
        this.cronExpression = Objects.requireNonNull(newExpression, "Cron 表达式不能为空");
        this.updatedAt = requireTime(changedAt);
    }

    public void changePrompt(String newPrompt, Instant changedAt) {
        this.prompt = requireText(newPrompt, "任务提示词不能为空");
        this.updatedAt = requireTime(changedAt);
    }

    public void moveTo(String newWorkingDir, Instant changedAt) {
        this.workingDir = requireText(newWorkingDir, "工作目录不能为空");
        this.updatedAt = requireTime(changedAt);
    }

    /** 按 PATCH 语义修改任务，null 字段保持不变。 */
    public void revise(String newName, String newCronExpression, String newPrompt,
                       String newWorkingDir, Instant changedAt) {
        Instant at = requireTime(changedAt);
        if (newName != null) {
            this.name = requireText(newName, "任务名称不能为空");
        }
        if (newCronExpression != null) {
            this.cronExpression = CronExpression.parse(newCronExpression);
        }
        if (newPrompt != null) {
            this.prompt = requireText(newPrompt, "任务提示词不能为空");
        }
        if (newWorkingDir != null) {
            this.workingDir = requireText(newWorkingDir, "工作目录不能为空");
        }
        this.updatedAt = at;
    }

    public void toggle(Instant changedAt) {
        this.enabled = !this.enabled;
        this.updatedAt = requireTime(changedAt);
    }

    public void recordRun(String sessionId, Instant ranAt) {
        this.lastSessionId = requireText(sessionId, "执行会话 ID 不能为空");
        this.lastRunAt = requireTime(ranAt);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static Instant requireTime(Instant value) {
        return Objects.requireNonNull(value, "变更时间不能为空");
    }
}
