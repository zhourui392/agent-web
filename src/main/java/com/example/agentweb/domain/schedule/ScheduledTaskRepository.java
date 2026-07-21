package com.example.agentweb.domain.schedule;

import java.time.Instant;
import java.util.List;

/**
 * Port for persisting scheduled tasks.
 * @author zhourui(V33215020)
 */
public interface ScheduledTaskRepository {

    /**
     * 新增任务记录.
     * @param task 待保存的任务聚合根
     */
    void save(ScheduledTask task);

    /**
     * 全量字段更新.
     * @param task 已修改的任务聚合根
     */
    void update(ScheduledTask task);

    /**
     * 按 ID 查询.
     * @param id 任务 ID
     * @return 任务聚合根,不存在则返回 null
     */
    ScheduledTask findById(String id);

    /**
     * 列出所有任务.
     * @return 任务列表,按创建时间倒序
     */
    List<ScheduledTask> findAll();

    /**
     * 列出启用中的任务,供调度器启动时注册.
     * @return 启用中的任务列表
     */
    List<ScheduledTask> findAllEnabled();

    /**
     * 按 ID 删除.
     * @param id 任务 ID
     */
    void deleteById(String id);

    /**
     * 更新最近一次执行的时间与对应 sessionId.
     * @param id 任务 ID
     * @param lastRunAt 触发时刻
     * @param lastSessionId 触发产生的会话 ID
     */
    void updateLastRun(String id, Instant lastRunAt, String lastSessionId);
}
