package com.example.agentweb.app.harness;

import com.example.agentweb.app.common.AfterCommitExecutor;
import com.example.agentweb.domain.harness.HarnessEvent;
import com.example.agentweb.domain.harness.HarnessRun;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@link HarnessRun#pullPendingEvents()} 取出本次操作产生的未发布事件，
 * 经 {@link AfterCommitExecutor} 在事务提交后推送给 {@link HarnessRunEventHub}。
 *
 * <p>每个 harness 写入口（Service 的 {@code @Transactional} 方法）在
 * {@code repository.update/add(run)} 之后调用 {@code publisher.publish(run)}。</p>
 *
 * @author zhourui(V33215020)
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessRunEventPublisher {

    private final AfterCommitExecutor afterCommitExecutor;
    private final HarnessRunEventHub eventHub;

    public HarnessRunEventPublisher(AfterCommitExecutor afterCommitExecutor, HarnessRunEventHub eventHub) {
        this.afterCommitExecutor = afterCommitExecutor;
        this.eventHub = eventHub;
    }

    public void publish(HarnessRun run) {
        List<HarnessEvent> pending = run.pullPendingEvents();
        if (pending.isEmpty()) {
            return;
        }
        final String runId = run.getId();
        final List<HarnessRunEvent> events = new ArrayList<HarnessRunEvent>(pending.size());
        for (HarnessEvent event : pending) {
            events.add(HarnessRunEvent.from(runId, event));
        }
        afterCommitExecutor.execute(new Runnable() {
            @Override
            public void run() {
                eventHub.publish(events);
            }
        });
    }
}