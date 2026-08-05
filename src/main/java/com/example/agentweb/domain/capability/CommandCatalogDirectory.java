package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 管理员明确配置的 Command Catalog 目录。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class CommandCatalogDirectory {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9_-]{0,127}");

    private final String directoryIdentifier;
    private final String absoluteDirectory;
    private final boolean enabled;

    private CommandCatalogDirectory(
            String directoryIdentifier, String absoluteDirectory, boolean enabled) {
        this.directoryIdentifier = requireIdentifier(directoryIdentifier);
        this.absoluteDirectory = requireAbsoluteDirectory(absoluteDirectory);
        this.enabled = enabled;
    }

    public static CommandCatalogDirectory create(
            String directoryIdentifier, String absoluteDirectory, boolean enabled) {
        return new CommandCatalogDirectory(directoryIdentifier, absoluteDirectory, enabled);
    }

    private static String requireIdentifier(String value) {
        String identifier = DomainText.require(
                value, "command catalog directory identifier", 128);
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "command catalog directory identifier must use a stable lowercase name");
        }
        return identifier;
    }

    private static String requireAbsoluteDirectory(String value) {
        String directory = DomainText.require(value, "command catalog directory", 4096);
        try {
            Path path = Paths.get(directory);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException(
                        "command catalog directory must be absolute");
            }
            return path.normalize().toString();
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException("command catalog directory is invalid", failure);
        }
    }
}
