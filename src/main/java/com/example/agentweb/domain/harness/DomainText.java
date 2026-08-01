package com.example.agentweb.domain.harness;

import java.time.Instant;

/**
 * Harness Domain 内部构造期校验，避免各值对象重复手写。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
final class DomainText {

    private DomainText() {
    }

    static String require(String value, String name) {
        return com.example.agentweb.domain.shared.DomainText.require(value, name);
    }

    static String require(String value, String name, int maxLength) {
        return com.example.agentweb.domain.shared.DomainText.require(value, name, maxLength);
    }

    static String requireSha256(String value, String name) {
        return com.example.agentweb.domain.shared.DomainText.requireSha256(value, name);
    }

    static Instant requireTime(Instant value, String name) {
        return com.example.agentweb.domain.shared.DomainText.requireTime(value, name);
    }
}
