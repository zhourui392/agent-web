package com.example.agentweb.infra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

/**
 * Thread pool for running agent processes and streaming output.
 *
 * <p>{@code @EnableScheduling} 在此统一开启注解式调度：注解式 {@code @Scheduled}
 * （如 refinery 会话轮询）需 {@code ScheduledAnnotationBeanPostProcessor} 才会被识别，
 * 缺它则静默失效；而编程式 {@code TaskScheduler.schedule}（动态任务）不依赖该开关。
 * 两者共用下方同一个 {@code taskScheduler} bean。</p>
 *
 * @author zhourui(V33215020)
 */
@Configuration
@EnableScheduling
public class ExecutorConfig {

    @Bean(name = "agentExecutor")
    public Executor agentExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(0);
        ex.setThreadNamePrefix("agent-exec-");
        ex.initialize();
        return ex;
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("sched-task-");
        scheduler.initialize();
        return scheduler;
    }
}

