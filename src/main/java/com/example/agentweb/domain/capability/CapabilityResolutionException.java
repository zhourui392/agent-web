package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;

import lombok.Getter;

/**
 * 能力解析 fail-closed 异常，code 可稳定映射到管理 API。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public class CapabilityResolutionException extends RuntimeException {

    private final String code;

    public CapabilityResolutionException(String code, String message) {
        super(message);
        this.code = DomainText.require(code, "capability error code", 80);
    }
}
