package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 管理员模板中的 tokenized 命令，不接受 Agent 生成的 Shell 字符串。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class DeploymentCommand {

    private final DeploymentStep step;
    private final List<String> arguments;

    public DeploymentCommand(DeploymentStep step, List<String> arguments) {
        if (step == null || arguments == null || arguments.isEmpty()
                || arguments.size() > 64 || arguments.contains(null)) {
            throw new IllegalArgumentException("deployment command is incomplete");
        }
        List<String> copy = new ArrayList<String>(arguments.size());
        for (String argument : arguments) {
            String normalized = DomainText.require(argument, "deployment command argument", 2048);
            if (normalized.indexOf('\0') >= 0 || normalized.indexOf('\n') >= 0
                    || normalized.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("deployment command argument is unsafe");
            }
            copy.add(normalized);
        }
        this.step = step;
        this.arguments = Collections.unmodifiableList(copy);
    }

    String canonical() {
        StringBuilder value = new StringBuilder(step.name());
        for (String argument : arguments) {
            value.append('\n').append(argument.length()).append(':').append(argument);
        }
        return value.toString();
    }
}
