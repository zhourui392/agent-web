package com.example.agentweb.app;

import com.example.agentweb.adapter.AgentGateway;
import com.example.agentweb.domain.AgentType;
import com.example.agentweb.domain.ChatMessage;
import com.example.agentweb.domain.ChatSession;
import com.example.agentweb.domain.ScheduledTask;
import com.example.agentweb.domain.ScheduledTaskRepository;
import com.example.agentweb.domain.SessionRepository;
import com.example.agentweb.infra.InMemorySessionRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ScheduledTaskServiceImpl implements ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskServiceImpl.class);

    private final ScheduledTaskRepository taskRepo;
    private final SessionRepository sessionRepository;
    private final InMemorySessionRepo inMemoryRepo;
    private final AgentGateway gateway;
    private DynamicTaskScheduler dynamicScheduler;

    public ScheduledTaskServiceImpl(ScheduledTaskRepository taskRepo,
                                    SessionRepository sessionRepository,
                                    InMemorySessionRepo inMemoryRepo,
                                    AgentGateway gateway) {
        this.taskRepo = taskRepo;
        this.sessionRepository = sessionRepository;
        this.inMemoryRepo = inMemoryRepo;
        this.gateway = gateway;
    }

    /**
     * Setter injection to break circular dependency with DynamicTaskScheduler.
     */
    public void setDynamicScheduler(DynamicTaskScheduler dynamicScheduler) {
        this.dynamicScheduler = dynamicScheduler;
    }

    @Override
    public ScheduledTask create(String name, String cronExpr, String prompt, String agentType, String workingDir) {
        // Validate cron expression
        try {
            new CronTrigger(cronExpr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的 Cron 表达式: " + cronExpr);
        }
        AgentType type = AgentType.valueOf(agentType);
        ScheduledTask task = new ScheduledTask(name, cronExpr, prompt, type, workingDir);
        taskRepo.save(task);
        if (dynamicScheduler != null) {
            dynamicScheduler.scheduleTask(task);
        }
        return task;
    }

    @Override
    public ScheduledTask update(String id, String name, String cronExpr, String prompt, String agentType, String workingDir) {
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
        if (agentType != null) task.setAgentType(AgentType.valueOf(agentType));
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
     */
    String doExecute(ScheduledTask task) {
        log.info("Executing scheduled task: {} ({})", task.getName(), task.getId());
        ChatSession session = ChatSession.forTask(task.getName(), task.getAgentType(), task.getWorkingDir());
        inMemoryRepo.save(session);
        sessionRepository.saveSession(session);

        // Persist user message (the prompt)
        sessionRepository.addMessage(session.getId(), new ChatMessage("user", task.getPrompt()));

        try {
            String output = gateway.runOnce(task.getAgentType(), task.getWorkingDir(), task.getPrompt());
            sessionRepository.addMessage(session.getId(), new ChatMessage("assistant", output));
        } catch (Exception e) {
            log.error("Scheduled task execution failed: {} ({})", task.getName(), task.getId(), e);
            sessionRepository.addMessage(session.getId(), new ChatMessage("assistant", "[error] " + e.getMessage()));
        }

        taskRepo.updateLastRun(task.getId(), Instant.now(), session.getId());
        log.info("Scheduled task completed: {} -> session {}", task.getName(), session.getId());
        return session.getId();
    }
}
