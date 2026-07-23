package com.example.agentweb.infra.schedule;

import com.example.agentweb.app.ScheduledTaskRegistrar;
import com.example.agentweb.app.ScheduledTaskServiceImpl;
import com.example.agentweb.domain.schedule.ScheduledTask;
import com.example.agentweb.domain.schedule.ScheduledTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * @author alex
 * @since 2026-07-23
 */
@Component
@Slf4j
public class DynamicTaskScheduler implements ScheduledTaskRegistrar {

    private final ScheduledTaskRepository taskRepo;
    private final ScheduledTaskServiceImpl taskService;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<String, ScheduledFuture<?>>();

    public DynamicTaskScheduler(ScheduledTaskRepository taskRepo,
                                ScheduledTaskServiceImpl taskService,
                                TaskScheduler taskScheduler) {
        this.taskRepo = taskRepo;
        this.taskService = taskService;
        this.taskScheduler = taskScheduler;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        // Inject self into service to break circular dependency
        taskService.setTaskRegistrar(this);

        List<ScheduledTask> tasks = taskRepo.findAllEnabled();
        log.info("Loading {} enabled scheduled tasks", tasks.size());
        for (ScheduledTask task : tasks) {
            refresh(task);
        }
    }

    @Override
    public void refresh(ScheduledTask task) {
        cancel(task.getId());
        if (!task.isEnabled()) {
            return;
        }
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                taskService.executeTask(task.getId());
                            } catch (Exception e) {
                                log.error("Scheduled task failed: {}", task.getName(), e);
                            }
                        }
                    },
                    new CronTrigger(task.getCronExpr())
            );
            scheduledFutures.put(task.getId(), future);
            log.info("Scheduled task registered: {} [{}]", task.getName(), task.getCronExpr());
        } catch (IllegalArgumentException e) {
            log.error("Invalid cron expression for task {}: {}", task.getName(), task.getCronExpr());
        }
    }

    @Override
    public void cancel(String taskId) {
        ScheduledFuture<?> future = scheduledFutures.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }
}
