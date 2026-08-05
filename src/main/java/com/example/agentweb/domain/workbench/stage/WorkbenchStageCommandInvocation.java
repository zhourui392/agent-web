package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.regex.Pattern;

/**
 * 动态 Stage 消息中由用户显式输入的 Slash Command 调用。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageCommandInvocation {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9:_-]{0,127}");

    private final String identifier;
    private final String arguments;

    private WorkbenchStageCommandInvocation(
            String identifier, String arguments) {
        String normalizedIdentifier = DomainText.require(
                identifier, "Stage Command identifier", 128);
        if (!IDENTIFIER_PATTERN.matcher(normalizedIdentifier).matches()) {
            throw new IllegalArgumentException(
                    "Stage Command identifier is invalid");
        }
        this.identifier = normalizedIdentifier;
        this.arguments = arguments == null ? "" : arguments.trim();
        if (this.arguments.length() > 32000) {
            throw new IllegalArgumentException(
                    "Stage Command arguments exceed the allowed size");
        }
    }

    public static WorkbenchStageCommandInvocation parse(String message) {
        String normalizedMessage = DomainText.require(
                message, "Stage Run message", 32000);
        if (!normalizedMessage.startsWith("/")) {
            return null;
        }
        int separator = firstWhitespace(normalizedMessage);
        String identifier = normalizedMessage.substring(
                1, separator < 0 ? normalizedMessage.length() : separator);
        String arguments = separator < 0
                ? "" : normalizedMessage.substring(separator + 1).trim();
        return new WorkbenchStageCommandInvocation(identifier, arguments);
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
