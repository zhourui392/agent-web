package com.example.agentweb.app;

import com.example.agentweb.domain.ScheduledTask;
import com.example.agentweb.domain.ScheduledTaskRepository;
import com.example.agentweb.infra.SqliteInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class DynamicTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(DynamicTaskScheduler.class);

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
        taskService.setDynamicScheduler(this);

        List<ScheduledTask> tasks = taskRepo.findAllEnabled();
        log.info("Loading {} enabled scheduled tasks", tasks.size());
        for (ScheduledTask task : tasks) {
            scheduleTask(task);
        }
    }

    public void scheduleTask(ScheduledTask task) {
        cancelTask(task.getId());
        if (!task.isEnabled()) {
            return;
        }
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                taskService.doExecute(task);
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

    public void cancelTask(String taskId) {
        ScheduledFuture<?> future = scheduledFutures.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }
}
