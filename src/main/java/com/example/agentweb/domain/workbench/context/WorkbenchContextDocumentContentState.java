package com.example.agentweb.domain.workbench.context;

/**
 * Context Document 已发布内容与当前文件观察结果的关系。
 *
 * @author alex
 * @since 2026-08-05
 */
public enum WorkbenchContextDocumentContentState {
    CURRENT,
    CONTENT_CHANGED,
    MISSING,
    UNREADABLE
}
