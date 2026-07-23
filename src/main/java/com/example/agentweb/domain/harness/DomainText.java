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
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    static String require(String value, String name, int maxLength) {
        String normalized = require(value, name);
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain at most " + maxLength + " characters");
        }
        return normalized;
    }

    static String requireSha256(String value, String name) {
        String normalized = require(value, name);
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return normalized;
    }

    static Instant requireTime(Instant value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
