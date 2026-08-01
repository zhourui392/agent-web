package com.example.agentweb.infra.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Provider 无关的 Runtime 输出脱敏与有界 Evidence 文本处理器。

 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeOutputRedactor {

    public static final String REDACTION_MARKER = "[REDACTED]";

    public String redactSecrets(String value, List<String> secrets) {
        Objects.requireNonNull(value, "runtime output must not be null");
        Objects.requireNonNull(secrets, "runtime secrets must not be null");
        List<String> longestFirst = new ArrayList<String>(secrets);
        for (String secret : longestFirst) {
            if (secret == null || secret.isEmpty()) {
                throw new IllegalArgumentException("runtime secret must not be empty");
            }
        }
        longestFirst.sort(Comparator.comparingInt(String::length).reversed());
        String redacted = value;
        for (String secret : longestFirst) {
            redacted = redacted.replace(secret, REDACTION_MARKER);
        }
        return redacted;
    }

    public String boundEvidenceLine(String value, int maximumCharacters) {
        Objects.requireNonNull(value, "runtime evidence line must not be null");
        if (maximumCharacters < 0) {
            throw new IllegalArgumentException(
                    "runtime evidence line limit must not be negative");
        }
        return value.length() <= maximumCharacters
                ? value : value.substring(0, maximumCharacters);
    }
}
