package com.example.agentweb.app.workbench.review;

import com.example.agentweb.domain.workbench.WorkbenchId;
import lombok.Getter;

/**
 * 保存当前 Review Opinion 的应用命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class SaveReviewOpinionCommand {

    private final WorkbenchId workbenchId;
    private final long expectedVersion;
    private final String content;

    public SaveReviewOpinionCommand(
            WorkbenchId workbenchId, long expectedVersion,
            String content) {
        this.workbenchId = workbenchId;
        this.expectedVersion = expectedVersion;
        this.content = content;
    }
}
