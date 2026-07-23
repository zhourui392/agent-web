package com.example.agentweb.app;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.app.agentrun.AgentRunContext;
import com.example.agentweb.app.agentrun.PromptAssemblyResult;
import com.example.agentweb.app.agentrun.PromptAssemblyService;
import com.example.agentweb.app.agentrun.RunForm;
import com.example.agentweb.app.agentrun.RunRecallPolicyFactory;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.schedule.ScheduledTask;
import com.example.agentweb.domain.schedule.ScheduledTaskRepository;
import com.example.agentweb.domain.chat.SessionCache;
import com.example.agentweb.domain.chat.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 定时任务应用服务实现，管理定时任务的 CRUD 和执行。
 * <p>任务执行时会创建独立 {@link ChatSession}，通过 {@link AgentGateway} 调用 CLI Agent，
 * 并将结果持久化为对话记录。</p>
 * @author zhourui(V33215020)
 */
@Service
@Slf4j
public class ScheduledTaskServiceImpl implements ScheduledTaskService {

    private final ScheduledTaskRepository taskRepo;
    private final SessionRepository sessionRepository;
    private final SessionCache sessionCache;
    private final AgentGateway gateway;
    private final CurrentUserProvider currentUserProvider;
    private final PromptAssemblyService promptAssemblyService;
    private final RunRecallPolicyFactory runRecallPolicyFactory;
    private DynamicTaskScheduler dynamicScheduler;

    public ScheduledTaskServiceImpl(ScheduledTaskRepository taskRepo,
                                    SessionRepository sessionRepository,
                                    SessionCache sessionCache,
                                    AgentGateway gateway,
                                    CurrentUserProvider currentUserProvider,
                                    PromptAssemblyService promptAssemblyService,
                                    RunRecallPolicyFactory runRecallPolicyFactory) {
        this.taskRepo = taskRepo;
        this.sessionRepository = sessionRepository;
        this.sessionCache = sessionCache;
        this.gateway = gateway;
        this.currentUserProvider = currentUserProvider;
        this.promptAssemblyService = promptAssemblyService;
        this.runRecallPolicyFactory = runRecallPolicyFactory;
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
        // 归属当前登录用户，用于任务列表隔离与执行归属
        task.setUserId(currentUserProvider.currentUserId());
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
        if (name != null) {
            task.setName(name);
        }
        if (prompt != null) {
            task.setPrompt(prompt);
        }
        if (workingDir != null) {
            task.setWorkingDir(workingDir);
        }
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
        final ChatSession session = ChatSession.forTask(task.getName(), AgentType.CODEX, task.getWorkingDir());
        // 透传任务归属到对话会话, 使执行产生的对话记录挂在任务创建者名下(后台执行无登录上下文, 只能取自任务)
        session.setUserId(task.getUserId());
        sessionCache.save(session);
        sessionRepository.saveSession(session);

        // Persist user message (the prompt)
        sessionRepository.addMessage(session.getId(), new ChatMessage("user", task.getPrompt()));

        final StreamChunkHandler handler = new StreamChunkHandler(sessionRepository, session.getId(), gateway, AgentType.CODEX);
        PromptAssemblyResult prompt = assemblePrompt(task);

        try {
            gateway.runStream(AgentType.CODEX, task.getWorkingDir(), prompt.getPrompt(),
                    session.getId(), null, null,
                    0L,
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

    private PromptAssemblyResult assemblePrompt(ScheduledTask task) {
        AgentRunContext context = AgentRunContext.builder()
                .originalInput(task.getPrompt())
                .runForm(RunForm.SCHEDULED)
                .sourceDomain(SourceType.GENERAL)
                .agentType(AgentType.CODEX)
                .workingDir(task.getWorkingDir())
                .recallPolicy(runRecallPolicyFactory.forRun(RunForm.SCHEDULED, SourceType.GENERAL))
                .build();
        try {
            return promptAssemblyService.assemble(context);
        } catch (RuntimeException e) {
            log.warn("scheduled-task-prompt-assembly-failed taskId={} workingDir={} reason={}",
                    task.getId(), task.getWorkingDir(), e.getMessage(), e);
            return new PromptAssemblyResult(task.getPrompt(), "assembly-fallback",
                    java.util.Collections.emptyList(), null, null,
                    java.util.Collections.emptyList(), "none");
        }
    }
}
