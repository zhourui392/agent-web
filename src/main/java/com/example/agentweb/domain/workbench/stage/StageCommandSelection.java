package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Stage Draft 选择的 Command 精确版本。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@EqualsAndHashCode
public final class StageCommandSelection {

    private final String identifier;
    private final String version;

    public StageCommandSelection(String identifier, String version) {
        this.identifier = DomainText.require(identifier, "Stage Command identifier", 128);
        this.version = DomainText.require(version, "Stage Command version", 80);
    }
}
