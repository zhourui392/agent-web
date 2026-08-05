package com.example.agentweb.domain.workbench.stage;

import lombok.Getter;

/**
 * Workbench Stage Catalog 稳定业务异常。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class StageCatalogException extends RuntimeException {

    private final String code;

    public StageCatalogException(String code, String message) {
        super(message);
        this.code = code;
    }
}
