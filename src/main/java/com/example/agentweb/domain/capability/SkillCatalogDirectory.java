package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 管理员明确配置且带信任来源的 Skill Catalog 目录。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class SkillCatalogDirectory {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9_-]{0,127}");

    private final String directoryIdentifier;
    private final String absoluteDirectory;
    private final SkillTrustSource trustSource;
    private final boolean enabled;

    private SkillCatalogDirectory(String directoryIdentifier, String absoluteDirectory,
                                  SkillTrustSource trustSource, boolean enabled) {
        this.directoryIdentifier = requireIdentifier(directoryIdentifier);
        this.absoluteDirectory = requireAbsoluteDirectory(absoluteDirectory);
        if (trustSource == null) {
            throw new IllegalArgumentException("skill catalog trust source must not be null");
        }
        this.trustSource = trustSource;
        this.enabled = enabled;
    }

    public static SkillCatalogDirectory create(
            String directoryIdentifier, String absoluteDirectory,
            SkillTrustSource trustSource, boolean enabled) {
        return new SkillCatalogDirectory(
                directoryIdentifier, absoluteDirectory, trustSource, enabled);
    }

    private static String requireIdentifier(String value) {
        String identifier = DomainText.require(
                value, "skill catalog directory identifier", 128);
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    "skill catalog directory identifier must use a stable lowercase name");
        }
        return identifier;
    }

    private static String requireAbsoluteDirectory(String value) {
        String directory = DomainText.require(value, "skill catalog directory", 4096);
        try {
            Path path = Paths.get(directory);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("skill catalog directory must be absolute");
            }
            return path.normalize().toString();
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException("skill catalog directory is invalid", failure);
        }
    }
}
