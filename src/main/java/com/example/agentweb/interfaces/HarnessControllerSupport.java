package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.InvalidHarnessIdempotencyKeyException;
import com.example.agentweb.domain.harness.HarnessStage;

import java.util.Locale;

/**
 * Harness Controller 间共享的请求参数解析辅助方法。
 *
 * <p>提取自各 Controller 的私有 {@code stage()} / {@code requireIdempotencyKey()} 方法，
 * 统一用 {@link Locale#ROOT} 避免平台默认 locale 差异。</p>
 *
 * @author zhourui(V33215020)
 */
public final class HarnessControllerSupport {

    public static final int MAXIMUM_IDEMPOTENCY_KEY_LENGTH = 128;

    private HarnessControllerSupport() {
    }

    public static HarnessStage stage(String value) {
        return HarnessStage.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static String requireIdempotencyKey(String value) {
        if (value == null || value.trim().isEmpty()
                || value.trim().length() > MAXIMUM_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidHarnessIdempotencyKeyException();
        }
        return value.trim();
    }
}