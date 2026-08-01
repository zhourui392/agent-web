package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Optional;

/**
 * Runtime 版本选择策略；显式区分受管配置版本和精确版本。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class RuntimeVersionPolicy {

    public enum Mode {
        CONFIGURED,
        EXACT
    }

    private final Mode mode;
    private final String exactVersion;

    private RuntimeVersionPolicy(Mode mode, String exactVersion) {
        this.mode = mode;
        this.exactVersion = exactVersion;
    }

    public static RuntimeVersionPolicy configured() {
        return new RuntimeVersionPolicy(Mode.CONFIGURED, "");
    }

    public static RuntimeVersionPolicy exact(String exactVersion) {
        return new RuntimeVersionPolicy(Mode.EXACT,
                DomainText.require(exactVersion, "exact runtime version", 160));
    }

    public Optional<String> exactVersion() {
        return exactVersion.isEmpty() ? Optional.empty() : Optional.of(exactVersion);
    }
}
