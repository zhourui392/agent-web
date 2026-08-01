package com.example.agentweb.domain.runtime;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Provider 执行策略可物化的高影响命令前缀事实。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeCommandPrefix {

    private final List<String> tokens;
    private final RuntimeHighImpactOperation operation;

    private RuntimeCommandPrefix(
            List<String> tokens, RuntimeHighImpactOperation operation) {
        if (tokens == null || tokens.isEmpty() || tokens.contains(null)) {
            throw new IllegalArgumentException(
                    "runtime command prefix must contain tokens");
        }
        List<String> copy = new ArrayList<String>();
        for (String token : tokens) {
            if (token.trim().isEmpty() || token.length() > 128
                    || containsControlCharacter(token)) {
                throw new IllegalArgumentException(
                        "runtime command prefix token is invalid");
            }
            copy.add(token);
        }
        this.tokens = Collections.unmodifiableList(copy);
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    public static RuntimeCommandPrefix of(
            RuntimeHighImpactOperation operation, String... tokens) {
        if (tokens == null) {
            throw new IllegalArgumentException(
                    "runtime command prefix tokens are required");
        }
        return new RuntimeCommandPrefix(
                java.util.Arrays.asList(tokens), operation);
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
