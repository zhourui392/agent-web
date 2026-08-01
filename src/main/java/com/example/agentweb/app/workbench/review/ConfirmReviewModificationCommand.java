package com.example.agentweb.app.workbench.review;

import com.example.agentweb.domain.workbench.WorkbenchId;
import lombok.Getter;

/**
 * 对 exact Opinion version/hash 进行显式人工 MODIFY 确认的应用命令。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ConfirmReviewModificationCommand {

    private final WorkbenchId workbenchId;
    private final long opinionVersion;
    private final String opinionHash;

    public ConfirmReviewModificationCommand(
            WorkbenchId workbenchId, long opinionVersion,
            String opinionHash) {
        this.workbenchId = workbenchId;
        this.opinionVersion = opinionVersion;
        this.opinionHash = opinionHash;
    }
}
