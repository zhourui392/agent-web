package com.example.agentweb.domain.runtime;

import lombok.Getter;

import java.util.Objects;
import java.util.Optional;

/**
 * 原始命令经过领域安全策略后得到的最小分类结果。
 *
 * <p>本对象不保留原始命令，避免后续事件、日志或 API 误透传其中的路径和 Secret。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeCommandAssessment {

    private final RuntimeCommandClass commandClass;
    private final RuntimeHighImpactOperation blockedOperation;

    private RuntimeCommandAssessment(
            RuntimeCommandClass commandClass,
            RuntimeHighImpactOperation blockedOperation) {
        this.commandClass = Objects.requireNonNull(
                commandClass, "commandClass");
        this.blockedOperation = blockedOperation;
    }

    public static RuntimeCommandAssessment allowed(
            RuntimeCommandClass commandClass) {
        return new RuntimeCommandAssessment(commandClass, null);
    }

    public static RuntimeCommandAssessment blocked(
            RuntimeCommandClass commandClass,
            RuntimeHighImpactOperation operation) {
        return new RuntimeCommandAssessment(commandClass,
                Objects.requireNonNull(operation, "operation"));
    }

    public boolean isBlocked() {
        return blockedOperation != null;
    }

    public Optional<RuntimeHighImpactOperation> blockedOperation() {
        return Optional.ofNullable(blockedOperation);
    }

    @Override
    public String toString() {
        return "RuntimeCommandAssessment{commandClass=" + commandClass
                + ", blockedOperation=" + blockedOperation + '}';
    }
}
