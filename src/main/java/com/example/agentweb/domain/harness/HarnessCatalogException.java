package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * Prompt/Skill Catalog 端口的 fail-closed 错误契约。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public class HarnessCatalogException extends RuntimeException {

    private final String code;

    public HarnessCatalogException(String code, String message) {
        super(message);
        this.code = DomainText.require(code, "catalog error code", 80);
    }

    public HarnessCatalogException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = DomainText.require(code, "catalog error code", 80);
    }
}
