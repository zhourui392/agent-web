package com.example.agentweb.domain.chatrun;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Objects;

/**
 * 外部业务来源绑定到 ChatRun 的最小中性引用。
 *
 * <p>普通 Chat 使用 {@link #none()}；Workbench 等外部来源必须同时提供来源引用和
 * 不可变执行上下文 ID。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ExecutionContextReference {

    private static final ExecutionContextReference NONE =
            new ExecutionContextReference(null, null, false);

    private final String originReference;
    private final String executionContextId;

    private ExecutionContextReference(
            String originReference, String executionContextId, boolean required) {
        if (!required) {
            if (originReference != null || executionContextId != null) {
                throw new IllegalArgumentException(
                        "absent execution context must not contain identity fields");
            }
            this.originReference = null;
            this.executionContextId = null;
            return;
        }
        this.originReference = DomainText.require(
                originReference, "execution origin reference", 500);
        this.executionContextId = DomainText.require(
                executionContextId, "execution context id", 128);
    }

    public static ExecutionContextReference none() {
        return NONE;
    }

    public static ExecutionContextReference of(
            String originReference, String executionContextId) {
        return new ExecutionContextReference(
                originReference, executionContextId, true);
    }

    public static ExecutionContextReference restore(
            String originReference, String executionContextId) {
        if (originReference == null && executionContextId == null) {
            return none();
        }
        if (originReference == null || executionContextId == null) {
            throw new IllegalArgumentException(
                    "execution context identity fields must be both present or both absent");
        }
        return of(originReference, executionContextId);
    }

    public boolean isPresent() {
        return originReference != null;
    }

    /**
     * 判断当前执行上下文是否属于指定外部来源。
     */
    public boolean matchesOriginReference(String expectedOriginReference) {
        String expected = DomainText.require(
                expectedOriginReference, "expected execution origin reference", 500);
        return expected.equals(originReference);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExecutionContextReference)) {
            return false;
        }
        ExecutionContextReference that = (ExecutionContextReference) other;
        return Objects.equals(originReference, that.originReference)
                && Objects.equals(executionContextId, that.executionContextId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(originReference, executionContextId);
    }
}
