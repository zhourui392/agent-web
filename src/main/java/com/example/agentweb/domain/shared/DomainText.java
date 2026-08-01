package com.example.agentweb.domain.shared;

import java.time.Instant;

/**
 * 领域值对象共享的构造期文本与时间校验。

 * @author alex
 * @since 2026-08-01
 */
public final class DomainText {

    private DomainText() {
    }

    public static String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public static String require(String value, String name, int maxLength) {
        String normalized = require(value, name);
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " must contain at most " + maxLength + " characters");
        }
        return normalized;
    }

    public static String requireSha256(String value, String name) {
        String normalized = require(value, name);
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return normalized;
    }

    public static Instant requireTime(Instant value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
