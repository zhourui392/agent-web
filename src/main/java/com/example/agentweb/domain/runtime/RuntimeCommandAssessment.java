package com.example.agentweb.domain.runtime;

import lombok.Getter;

import java.util.Objects;

/**
 * 原始命令经过分类后得到的最小分类结果。
 *
 * <p>本对象不保留原始命令，避免后续事件、日志或 API 误透传其中的路径和 Secret。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeCommandAssessment {

    private final RuntimeCommandClass commandClass;

    private RuntimeCommandAssessment(RuntimeCommandClass commandClass) {
        this.commandClass = Objects.requireNonNull(
                commandClass, "commandClass");
    }

    public static RuntimeCommandAssessment of(
            RuntimeCommandClass commandClass) {
        return new RuntimeCommandAssessment(commandClass);
    }

    @Override
    public String toString() {
        return "RuntimeCommandAssessment{commandClass=" + commandClass + '}';
    }
}
