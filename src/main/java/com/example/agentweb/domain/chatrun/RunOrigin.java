package com.example.agentweb.domain.chatrun;

/**
 * ChatRun 的中性业务来源；执行计划按该值注册分派，不依赖页面或 Provider 类型。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum RunOrigin {

    CHAT(false),
    WORKBENCH(true);

    private final boolean executionContextRequired;

    RunOrigin(boolean executionContextRequired) {
        this.executionContextRequired = executionContextRequired;
    }

    public void requireCompatible(
            ChatRunId runId, ExecutionContextReference contextReference) {
        if (runId == null) {
            throw new IllegalArgumentException("chat run id must not be null");
        }
        if (contextReference == null
                || executionContextRequired != contextReference.isPresent()) {
            throw new IllegalArgumentException(
                    "chat run origin and execution context must be compatible");
        }
        if (executionContextRequired
                && !runId.getValue().equals(
                contextReference.getExecutionContextId())) {
            throw new IllegalArgumentException(
                    "external execution context id must equal the chat run id");
        }
    }
}
