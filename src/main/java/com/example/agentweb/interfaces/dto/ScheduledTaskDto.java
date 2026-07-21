package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;
import com.example.agentweb.domain.schedule.ScheduledTask;

/**
 * @author zhourui(V33215020)
 */
@Getter
@Setter
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
}
