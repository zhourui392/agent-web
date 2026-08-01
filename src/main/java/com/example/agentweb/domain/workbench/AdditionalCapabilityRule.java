package com.example.agentweb.domain.workbench;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 高级用户为单个 Phase 追加的、只能收紧既有规则的文本偏好。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class AdditionalCapabilityRule {

    private static final int MAX_CONFIGURABLE_CHARACTERS = 16_000;

    private final String value;

    private AdditionalCapabilityRule(String value) {
        this.value = value;
    }

    public static AdditionalCapabilityRule create(
            String rawValue, int maximumCharacters) {
        requireMaximum(maximumCharacters);
        if (rawValue == null) {
            throw new IllegalArgumentException(
                    "additional capability rule must not be null");
        }
        String normalized = normalize(rawValue);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "additional capability rule must not be blank");
        }
        requireSafeCharacters(normalized);
        if (normalized.codePointCount(0, normalized.length())
                > maximumCharacters) {
            throw new IllegalArgumentException(
                    "additional capability rule exceeds the configured limit");
        }
        return new AdditionalCapabilityRule(normalized);
    }

    public static AdditionalCapabilityRule optional(
            String rawValue, int maximumCharacters) {
        requireMaximum(maximumCharacters);
        if (rawValue == null) {
            return null;
        }
        String normalized = normalize(rawValue);
        if (normalized.isEmpty()) {
            return null;
        }
        return create(normalized, maximumCharacters);
    }

    public static AdditionalCapabilityRule restore(String persistedValue) {
        AdditionalCapabilityRule restored = create(
                persistedValue, MAX_CONFIGURABLE_CHARACTERS);
        if (!restored.value.equals(persistedValue)) {
            throw new IllegalArgumentException(
                    "persisted additional capability rule is not canonical");
        }
        return restored;
    }

    private static void requireMaximum(int maximumCharacters) {
        if (maximumCharacters < 1
                || maximumCharacters > MAX_CONFIGURABLE_CHARACTERS) {
            throw new IllegalArgumentException(
                    "additional capability rule maximum must be between 1 and "
                            + MAX_CONFIGURABLE_CHARACTERS);
        }
    }

    private static void requireSafeCharacters(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if ((Character.isISOControl(codePoint)
                    && codePoint != '\n' && codePoint != '\t')
                    || isSurrogateCodePoint(codePoint)) {
                throw new IllegalArgumentException(
                        "additional capability rule contains a forbidden control character");
            }
            offset += Character.charCount(codePoint);
        }
    }

    private static boolean isSurrogateCodePoint(int codePoint) {
        return codePoint >= Character.MIN_SURROGATE
                && codePoint <= Character.MAX_SURROGATE;
    }

    private static String normalize(String value) {
        return trimOuterWhitespace(
                value.replace("\r\n", "\n").replace('\r', '\n'));
    }

    private static String trimOuterWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }
}
