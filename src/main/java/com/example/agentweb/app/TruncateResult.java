package com.example.agentweb.app;

import lombok.Getter;
import lombok.Setter;

/**
 * 截断会话消息的应用结果。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
@Setter
public class TruncateResult {
    private int deletedCount;
    private String prefillContent;
    private boolean resumeIdCleared;

    public TruncateResult(int deletedCount, String prefillContent, boolean resumeIdCleared) {
        this.deletedCount = deletedCount;
        this.prefillContent = prefillContent;
        this.resumeIdCleared = resumeIdCleared;
    }
}
