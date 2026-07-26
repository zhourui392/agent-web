package com.example.agentweb.app.harness;

/**
 * 订阅者的有界队列溢出时抛出。
 *
 * @author zhourui(V33215020)
 */
public class SlowHarnessSubscriberException extends RuntimeException {

    public SlowHarnessSubscriberException(String runId) {
        super("harness run subscriber is too slow: " + runId);
    }
}