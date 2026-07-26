package com.example.agentweb.app.harness;

import java.util.List;

/**
 * 事务提交后的进程内事件 fan-out 端口。
 *
 * @author zhourui(V33215020)
 */
public interface HarnessRunEventHub {

    HarnessRunEventSubscription open(String runId, HarnessRunEventConsumer consumer);

    void publish(List<HarnessRunEvent> events);

    int subscriberCount(String runId);

    int totalSubscriberCount();

    long slowConsumerClosedTotal();
}