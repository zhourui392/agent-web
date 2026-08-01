package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

/**
 * 可信 Capability Catalog 的稳定错误契约。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public class CapabilityCatalogException extends RuntimeException {

    private final String code;

    public CapabilityCatalogException(String code, String message) {
        super(message);
        this.code = DomainText.require(code, "catalog error code", 80);
    }

    public CapabilityCatalogException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = DomainText.require(code, "catalog error code", 80);
    }
}
