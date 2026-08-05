package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 修改 Stage Catalog 的真实管理员。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@EqualsAndHashCode
public final class StageCatalogEditor {

    private final String actorId;
    private final String actorName;

    private StageCatalogEditor(String actorId, String actorName) {
        this.actorId = DomainText.require(actorId, "Stage editor actor id", 128);
        this.actorName = DomainText.require(actorName, "Stage editor actor name", 256);
    }

    public static StageCatalogEditor create(String actorId, String actorName) {
        return new StageCatalogEditor(actorId, actorName);
    }
}
