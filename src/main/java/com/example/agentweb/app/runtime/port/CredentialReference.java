package com.example.agentweb.app.runtime.port;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 不含 Secret 明文的 Runtime 凭据来源引用。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class CredentialReference {

    private static final Pattern ENVIRONMENT_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public enum Source {
        SYSTEM_CONFIGURATION,
        ENVIRONMENT
    }

    private final Source source;
    private final String environmentVariable;

    private CredentialReference(Source source, String environmentVariable) {
        this.source = source;
        this.environmentVariable = environmentVariable;
    }

    public static CredentialReference systemConfiguration() {
        return new CredentialReference(Source.SYSTEM_CONFIGURATION, "");
    }

    public static CredentialReference environment(String environmentVariable) {
        String reference = DomainText.require(
                environmentVariable, "credential environment reference", 160);
        if (!ENVIRONMENT_NAME.matcher(reference).matches()) {
            throw new IllegalArgumentException(
                    "credential environment reference must be an environment variable name");
        }
        return new CredentialReference(Source.ENVIRONMENT, reference);
    }

    public Optional<String> environmentVariable() {
        return environmentVariable.isEmpty()
                ? Optional.empty() : Optional.of(environmentVariable);
    }
}
