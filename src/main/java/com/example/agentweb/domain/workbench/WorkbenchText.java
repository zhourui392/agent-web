package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;

/**
 * Workbench 人工文本的控制字符约束。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkbenchText {

    private WorkbenchText() {
    }

    static String requireUntrustedText(String value, String name, int maxLength) {
        return rejectControlCharacters(DomainText.require(value, name, maxLength), name);
    }

    static String optionalUntrustedText(String value, String name, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return requireUntrustedText(value, name, maxLength);
    }

    static String allowEmptyUntrustedText(String value, String name, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " must contain at most " + maxLength + " characters");
        }
        return rejectControlCharacters(normalized, name);
    }

    private static String rejectControlCharacters(String value, String name) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t') {
                throw new IllegalArgumentException(name + " contains a control character");
            }
        }
        return value;
    }
}
