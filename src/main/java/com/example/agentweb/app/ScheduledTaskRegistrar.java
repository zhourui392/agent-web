package com.example.agentweb.app;

import com.example.agentweb.domain.schedule.ScheduledTask;

/**
 * 定时任务运行时注册端口。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface ScheduledTaskRegistrar {

    /**
     * Refresh the runtime registration for the latest task state.
     *
     * @param task latest scheduled task aggregate
     */
    void refresh(ScheduledTask task);

    /**
     * Cancel the runtime registration for a task.
     *
     * @param taskId scheduled task identifier
     */
    void cancel(String taskId);
}
