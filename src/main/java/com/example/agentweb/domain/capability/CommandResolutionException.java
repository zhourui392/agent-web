package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

/**
 * Workbench Command 解析与展开失败。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public class CommandResolutionException extends RuntimeException {

    private final String code;

    public CommandResolutionException(String code, String message) {
        super(message);
        this.code = DomainText.require(code, "command resolution error code", 80);
    }
}
