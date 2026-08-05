package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可由用户显式调用的版本化 Slash Command Prompt Template。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class CommandDefinition {

    static final int MAX_EXPANDED_PROMPT_LENGTH = 65536;
    private static final int MAX_PROMPT_TEMPLATE_LENGTH = 65536;
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9:_-]{0,127}");
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\$[A-Z][A-Z0-9_]*");
    private static final String ARGUMENTS_PLACEHOLDER = "$ARGUMENTS";

    private final String identifier;
    private final String version;
    private final String displayName;
    private final String description;
    private final String argumentHint;
    private final String promptTemplate;
    private final String sourceDirectoryIdentifier;
    private final String contentHash;
    private final Instant discoveredAt;

    private CommandDefinition(
            String identifier, String version, String displayName, String description,
            String argumentHint, String promptTemplate,
            String sourceDirectoryIdentifier, Instant discoveredAt) {
        this.identifier = requireIdentifier(identifier);
        this.version = DomainText.require(version, "command version", 80);
        this.displayName = DomainText.require(displayName, "command display name", 256);
        this.description = DomainText.require(description, "command description", 2000);
        this.argumentHint = optionalText(argumentHint, "command argument hint", 512);
        this.promptTemplate = DomainText.require(
                promptTemplate, "command prompt template", MAX_PROMPT_TEMPLATE_LENGTH);
        requireSupportedPlaceholders(this.promptTemplate);
        this.sourceDirectoryIdentifier =
                requireSourceDirectoryIdentifier(sourceDirectoryIdentifier);
        this.discoveredAt = DomainText.requireTime(discoveredAt, "command discovery time");
        this.contentHash = calculateContentHash();
    }

    public static CommandDefinition create(
            String identifier, String version, String displayName, String description,
            String argumentHint, String promptTemplate,
            String sourceDirectoryIdentifier, Instant discoveredAt) {
        return new CommandDefinition(identifier, version, displayName, description,
                argumentHint, promptTemplate, sourceDirectoryIdentifier, discoveredAt);
    }

    public ResolvedCommandBinding resolve(String expectedContentHash, String arguments) {
        String expected = DomainText.requireSha256(
                expectedContentHash, "expected command content hash");
        if (!contentHash.equals(expected)) {
            throw new CommandResolutionException(
                    "WORKBENCH_COMMAND_CONTENT_CHANGED",
                    "command content no longer matches the frozen stage definition");
        }
        String normalizedArguments = arguments == null ? "" : arguments.trim();
        String expandedPrompt = promptTemplate.replace(
                ARGUMENTS_PLACEHOLDER, normalizedArguments);
        if (expandedPrompt.length() > MAX_EXPANDED_PROMPT_LENGTH) {
            throw new CommandResolutionException(
                    "WORKBENCH_COMMAND_EXPANSION_TOO_LARGE",
                    "expanded command prompt exceeds the allowed size");
        }
        return new ResolvedCommandBinding(
                identifier, version, contentHash, expandedPrompt);
    }

    private static String requireIdentifier(String value) {
        String normalized = DomainText.require(value, "command identifier", 128);
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "command identifier must match [a-z0-9][a-z0-9:_-]{0,127}");
        }
        return normalized;
    }

    private static String requireSourceDirectoryIdentifier(String value) {
        String normalized = DomainText.require(
                value, "command source directory identifier", 128);
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "command source directory identifier must use a stable lowercase name");
        }
        return normalized;
    }

    private static String optionalText(String value, String name, int maximumLength) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        return DomainText.require(value, name, maximumLength);
    }

    private static void requireSupportedPlaceholders(String promptTemplate) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(promptTemplate);
        while (matcher.find()) {
            if (!ARGUMENTS_PLACEHOLDER.equals(matcher.group())) {
                throw new CommandResolutionException(
                        "WORKBENCH_COMMAND_TEMPLATE_INVALID",
                        "unsupported command placeholder: " + matcher.group());
            }
        }
    }

    private String calculateContentHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "identifier", identifier);
        CanonicalHashing.appendFramed(canonical, "version", version);
        CanonicalHashing.appendFramed(canonical, "displayName", displayName);
        CanonicalHashing.appendFramed(canonical, "description", description);
        CanonicalHashing.appendFramed(canonical, "argumentHint", argumentHint);
        CanonicalHashing.appendFramed(canonical, "promptTemplate", promptTemplate);
        return CanonicalHashing.sha256(canonical.toString());
    }
}
