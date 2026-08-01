package com.example.agentweb.app.workbench.document;

import lombok.Getter;

import java.util.Objects;

/**
 * 不回显绝对路径、文件正文或底层 I/O 细节的文档访问失败。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class DocumentOperationException extends RuntimeException {

    private final DocumentFailureCode code;

    public DocumentOperationException(
            DocumentFailureCode code, String safeMessage) {
        super(safeMessage);
        this.code = Objects.requireNonNull(code, "code");
    }

    public DocumentOperationException(
            DocumentFailureCode code, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = Objects.requireNonNull(code, "code");
    }
}
