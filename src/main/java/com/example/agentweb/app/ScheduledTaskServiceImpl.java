package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.AgentType;
import com.example.agentweb.domain.ChatMessage;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.ScheduledTask;
import com.example.agentweb.domain.ScheduledTaskRepository;
import com.example.agentweb.domain.SessionCache;
import com.example.agentweb.domain.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 定时任务应用服务实现，管理定时任务的 CRUD 和执行。
 * <p>任务执行时会创建独立 {@link ChatSession}，通过 {@link AgentGateway} 调用 CLI Agent，
 * 并将结果持久化为对话记录。</p>
 */
@Service
public class ScheduledTaskServiceImpl implements ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskServiceImpl.class);

    private final ScheduledTaskRepository taskRepo;
    private final SessionRepository sessionRepository;
    private final SessionCache sessionCache;
    private final AgentGateway gateway;
    private DynamicTaskScheduler dynamicScheduler;

    public ScheduledTaskServiceImpl(ScheduledTaskRepository taskRepo,
                                    SessionRepository sessionRepository,
                                    SessionCache sessionCache,
                                    AgentGateway gateway) {
        this.taskRepo = taskRepo;
        this.sessionRepository = sessionRepository;
        this.sessionCache = sessionCache;
        this.gateway = gateway;
    }

    /**
     * Setter injection to break circular dependency with DynamicTaskScheduler.
     */
    public void setDynamicScheduler(DynamicTaskScheduler dynamicScheduler) {
        this.dynamicScheduler = dynamicScheduler;
    }

    @Override
    public ScheduledTask create(String name, String cronExpr, String prompt, String workingDir) {
        // Validate cron expression
        try {
            new CronTrigger(cronExpr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的 Cron 表达式: " + cronExpr);
        }
        ScheduledTask task = new ScheduledTask(name, cronExpr, prompt, workingDir);
        taskRepo.save(task);
        if (dynamicScheduler != null) {
            dynamicScheduler.scheduleTask(task);
        }
        return task;
    }

    @Override
    public ScheduledTask update(String id, String name, String cronExpr, String prompt, String workingDir) {
        ScheduledTask task = taskRepo.findById(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        if (cronExpr != null) {
            try {
                new CronTrigger(cronExpr);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("无效的 Cron 表达式: " + cronExpr);
            }
            task.setCronExpr(cronExpr);
        }
        if (name != null) task.setName(name);
        if (prompt != null) task.setPrompt(prompt);
        if (workingDir != null) task.setWorkingDir(workingDir);
        task.setUpdatedAt(Instant.now());
        taskRepo.update(task);
        if (dynamicScheduler != null) {
            dynamicScheduler.scheduleTask(task);
        }
        return task;
    }

    @Override
    public void delete(String id) {
        taskRepo.deleteById(id);
        if (dynamicScheduler != null) {
            dynamicScheduler.cancelTask(id);
        }
    }

    @Override
    public void toggleEnabled(String id) {
        ScheduledTask task = taskRepo.findById(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        task.setEnabled(!task.isEnabled());
        task.setUpdatedAt(Instant.now());
        taskRepo.update(task);
        if (dynamicScheduler != null) {
            if (task.isEnabled()) {
                dynamicScheduler.scheduleTask(task);
            } else {
                dynamicScheduler.cancelTask(id);
            }
        }
    }

    @Override
    public List<ScheduledTask> listAll() {
        return taskRepo.findAll();
    }

    @Override
    public ScheduledTask getById(String id) {
        return taskRepo.findById(id);
    }

    @Override
    public String executeTask(String taskId) {
        ScheduledTask task = taskRepo.findById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return doExecute(task);
    }

    /**
     * Core execution logic shared by cron trigger and manual trigger.
     * Uses runStream (same as chat) so that output format, resumeId extraction,
     * and process management are fully consistent with interactive conversations.
     */
    String doExecute(ScheduledTask task) {
        log.info("Executing scheduled task: {} ({})", task.getName(), task.getId());
        final ChatSession session = ChatSession.forTask(task.getName(), AgentType.CLAUDE, task.getWorkingDir());
        sessionCache.save(session);
        sessionRepository.saveSession(session);

        // Persist user message (the prompt)
        sessionRepository.addMessage(session.getId(), new ChatMessage("user", task.getPrompt()));

        final StreamChunkHandler handler = new StreamChunkHandler(sessionRepository, session.getId());

        try {
            gateway.runStream(AgentType.CLAUDE, task.getWorkingDir(), task.getPrompt(),
                    session.getId(), null, null,
                    handler.onChunk(null),
                    handler.onExit(null));
        } catch (Exception e) {
            log.error("Scheduled task execution failed: {} ({})", task.getName(), task.getId(), e);
            sessionRepository.addMessage(session.getId(), new ChatMessage("assistant", "[error] " + e.getMessage()));
        }

        taskRepo.updateLastRun(task.getId(), Instant.now(), session.getId());
        log.info("Scheduled task completed: {} -> session {}", task.getName(), session.getId());
        return session.getId();
    }
}
