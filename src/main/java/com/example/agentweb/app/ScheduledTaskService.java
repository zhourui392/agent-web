package com.example.agentweb.app;

import com.example.agentweb.domain.schedule.ScheduledTask;

import java.util.List;

/**
 * @author zhourui(V33215020)
 */
public interface ScheduledTaskService {

    /**
     * 创建定时任务，落库后由运行时注册端口注册触发。
     * @param name 任务名
     * @param cronExpr Spring CronTrigger 兼容表达式
     * @param prompt 触发时发送给 agent 的提示词
     * @param workingDir 工作目录
     * @return 已创建的任务聚合根
     */
    ScheduledTask create(String name, String cronExpr, String prompt, String workingDir);

    /**
     * 更新定时任务,任一参数为 null 表示保持原值.
     * @param id 任务 ID
     * @param name 任务名
     * @param cronExpr 新的 Cron 表达式
     * @param prompt 新的提示词
     * @param workingDir 新的工作目录
     * @return 更新后的任务聚合根
     */
    ScheduledTask update(String id, String name, String cronExpr, String prompt, String workingDir);

    /**
     * 删除定时任务,同时注销已注册的触发.
     * @param id 任务 ID
     */
    void delete(String id);

    /**
     * 切换启用/禁用状态.
     * @param id 任务 ID
     */
    void toggleEnabled(String id);

    /**
     * 列出全部定时任务,按创建时间倒序.
     * @return 任务列表
     */
    List<ScheduledTask> listAll();

    /**
     * 按 ID 查询任务.
     * @param id 任务 ID
     * @return 任务聚合根,不存在则返回 null
     */
    ScheduledTask getById(String id);

    /**
     * Execute a task immediately. Returns the created session ID.
     * @param taskId 任务 ID
     * @return 触发后创建的会话 ID
     */
    String executeTask(String taskId);
}
