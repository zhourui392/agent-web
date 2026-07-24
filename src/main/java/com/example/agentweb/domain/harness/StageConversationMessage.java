package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * 阶段对话中的一条用户修订指令，也是审计事件 detail 的稳定编码。
 *
 * @author alex
 * @since 2026-07-24
 */
@Getter
public final class StageConversationMessage {

    private static final String COMMAND_PREFIX = "command=";
    private static final String ATTEMPT_PREFIX = "attempt=";

    private final String commandId;
    private final int attemptNumber;
    private final String content;

    public StageConversationMessage(String commandId, int attemptNumber, String content) {
        this.commandId = DomainText.requireSha256(commandId, "conversation command id");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("conversation attempt number must be positive");
        }
        this.attemptNumber = attemptNumber;
        this.content = DomainText.require(content, "conversation message", 20000);
    }

    public String encode() {
        return COMMAND_PREFIX + commandId + '\n'
                + ATTEMPT_PREFIX + attemptNumber + '\n'
                + content;
    }

    public static StageConversationMessage decode(String detail) {
        if (detail == null) {
            throw new IllegalArgumentException("conversation event detail must not be null");
        }
        int firstBreak = detail.indexOf('\n');
        int secondBreak = firstBreak < 0 ? -1 : detail.indexOf('\n', firstBreak + 1);
        if (firstBreak < 0 || secondBreak < 0
                || !detail.startsWith(COMMAND_PREFIX)
                || !detail.substring(firstBreak + 1).startsWith(ATTEMPT_PREFIX)) {
            throw new IllegalArgumentException("conversation event detail is malformed");
        }
        String commandId = detail.substring(COMMAND_PREFIX.length(), firstBreak);
        String attempt = detail.substring(firstBreak + 1 + ATTEMPT_PREFIX.length(), secondBreak);
        try {
            return new StageConversationMessage(
                    commandId, Integer.parseInt(attempt), detail.substring(secondBreak + 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("conversation attempt number is malformed", ex);
        }
    }
}
