package com.example.agentweb.app.workbench.document;

/**
 * Scoped Document 的稳定应用错误码。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum DocumentFailureCode {
    WORKBENCH_DOCUMENT_REQUEST_INVALID,
    WORKBENCH_DOCUMENT_NOT_FOUND,
    WORKBENCH_DOCUMENT_TOO_LARGE,
    WORKBENCH_DOCUMENT_UNSUPPORTED,
    WORKBENCH_DOCUMENT_CHANGED_DURING_READ
}
