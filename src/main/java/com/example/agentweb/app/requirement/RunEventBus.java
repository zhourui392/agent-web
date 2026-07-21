package com.example.agentweb.app.requirement;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * run 事件广播总线（需求线 run-stream）。
 *
 * <p>维护 {@code streamKey → Set<SseEmitter>} 多订阅映射，发布事件时广播给该流所有订阅者；
 * 任一订阅者发送失败自动移除，不影响其他订阅者。终态时由调用方显式 {@link #close} 关闭所有订阅。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-09
 */
@Component
@Slf4j
public class RunEventBus {

    private final ConcurrentMap<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public void subscribe(String streamKey, SseEmitter emitter) {
        Set<SseEmitter> set = subscribers.computeIfAbsent(streamKey, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        log.debug("event-subscribed streamKey={} subscriberCount={}", streamKey, set.size());
        emitter.onCompletion(() -> unsubscribe(streamKey, emitter));
        emitter.onError(t -> unsubscribe(streamKey, emitter));
        emitter.onTimeout(() -> unsubscribe(streamKey, emitter));
    }

    public void unsubscribe(String streamKey, SseEmitter emitter) {
        Set<SseEmitter> set = subscribers.get(streamKey);
        if (set != null && set.remove(emitter)) {
            log.debug("event-unsubscribed streamKey={} subscriberCount={}", streamKey, set.size());
        }
    }

    public void publish(String streamKey, int eventSeq, String type, String payload) {
        Set<SseEmitter> targets = subscribers.get(streamKey);
        if (targets == null || targets.isEmpty()) {
            log.debug("event-publish-no-subscriber streamKey={} eventSeq={} type={}", streamKey, eventSeq, type);
            return;
        }
        log.debug("event-publish streamKey={} eventSeq={} type={} subscriberCount={} payloadLen={}",
                streamKey, eventSeq, type, targets.size(), payload == null ? 0 : payload.length());
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .id(String.valueOf(eventSeq))
                .name(type)
                .data(payload);
        for (SseEmitter emitter : targets) {
            sendOrRemove(streamKey, emitter, builder);
        }
    }

    public void close(String streamKey) {
        Set<SseEmitter> targets = subscribers.remove(streamKey);
        if (targets == null) {
            return;
        }
        log.info("event-bus-close streamKey={} pendingSubscribers={}", streamKey, targets.size());
        for (SseEmitter emitter : targets) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // emitter 已被外部完成或异常，吞掉以保证其他订阅者不受影响
            }
        }
    }

    private void sendOrRemove(String streamKey, SseEmitter emitter, SseEmitter.SseEventBuilder builder) {
        try {
            emitter.send(builder);
        } catch (Exception e) {
            log.debug("event-send-failed streamKey={} reason={}", streamKey, e.getMessage());
            unsubscribe(streamKey, emitter);
        }
    }
}
