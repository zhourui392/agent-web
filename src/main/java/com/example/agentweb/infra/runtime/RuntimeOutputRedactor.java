package com.example.agentweb.infra.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Provider 无关的 Runtime 输出脱敏与有界 Evidence 文本处理器。

 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeOutputRedactor {

    public static final String REDACTION_MARKER = "[REDACTED]";
    private static final Pattern ANSI_ESCAPE_SEQUENCE =
            Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");

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

    public String sanitizeDisplayText(String value) {
        Objects.requireNonNull(value, "runtime display text must not be null");
        String withoutAnsi = ANSI_ESCAPE_SEQUENCE.matcher(value).replaceAll("");
        StringBuilder sanitized = new StringBuilder(withoutAnsi.length());
        for (int index = 0; index < withoutAnsi.length(); index++) {
            char character = withoutAnsi.charAt(index);
            if (!Character.isISOControl(character)
                    || character == '\n' || character == '\r'
                    || character == '\t') {
                sanitized.append(character);
            }
        }
        return sanitized.toString();
    }
}
