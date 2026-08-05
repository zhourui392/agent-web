package com.example.agentweb.interfaces.workbench.admin.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 发布或停用 Stage 时提交的 Catalog 乐观版本。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@Setter
public final class StageCatalogVersionRequest {

    private Long expectedStageCatalogVersion;

    public long requireExpectedStageCatalogVersion() {
        if (expectedStageCatalogVersion == null
                || expectedStageCatalogVersion.longValue() < 1L) {
            throw new IllegalArgumentException(
                    "expected Stage Catalog version must be positive");
        }
        return expectedStageCatalogVersion.longValue();
    }
}
