package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.CommandCatalog;
import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.example.agentweb.domain.capability.CommandDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 只扫描管理员明确配置目录的 Markdown Command Catalog。
 *
 * @author alex
 * @since 2026-08-05
 */
public class FileSystemCommandCatalog implements CommandCatalog {

    private static final long MAX_COMMAND_FILE_BYTES = 65536L;
    private static final int MAX_SCAN_DEPTH = 16;

    private final List<CommandCatalogDirectory> directories;
    private final Clock clock;

    public FileSystemCommandCatalog(
            List<CommandCatalogDirectory> directories, Clock clock) {
        if (directories == null || directories.contains(null)) {
            throw new IllegalArgumentException(
                    "command catalog directories must not be null or contain null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("command catalog clock must not be null");
        }
        this.directories = Collections.unmodifiableList(
                new ArrayList<CommandCatalogDirectory>(directories));
        this.clock = clock;
    }

    @Override
    public List<CommandDefinition> discover() {
        Map<String, CommandDefinition> definitions = new HashMap<String, CommandDefinition>();
        for (CommandCatalogDirectory directory : directories) {
            if (directory.isEnabled()) {
                discoverDirectory(directory, definitions);
            }
        }
        List<CommandDefinition> discovered =
                new ArrayList<CommandDefinition>(definitions.values());
        discovered.sort(Comparator.comparing(CommandDefinition::getIdentifier)
                .thenComparing(CommandDefinition::getVersion));
        return Collections.unmodifiableList(discovered);
    }

    private void discoverDirectory(
            CommandCatalogDirectory directory,
            Map<String, CommandDefinition> definitions) {
        Path root = requireRealDirectory(directory);
        for (Path commandFile : commandFiles(root)) {
            CommandDefinition candidate = parseCommand(directory, root, commandFile);
            String key = candidate.getIdentifier() + "\u0000" + candidate.getVersion();
            CommandDefinition existing = definitions.putIfAbsent(key, candidate);
            if (existing != null) {
                rejectDuplicate(existing, candidate);
            }
        }
    }

    private Path requireRealDirectory(CommandCatalogDirectory directory) {
        Path configured = Path.of(directory.getAbsoluteDirectory()).normalize();
        rejectSymbolicLinkSegments(configured);
        try {
            Path real = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("CATALOG_ROOT_INVALID",
                        "command catalog root must be a directory");
            }
            return real;
        } catch (IOException failure) {
            throw new CapabilityCatalogException("CATALOG_ROOT_INVALID",
                    "command catalog root is not accessible", failure);
        }
    }

    private void rejectSymbolicLinkSegments(Path configured) {
        Path current = configured.getRoot();
        for (Path segment : configured) {
            current = current == null ? segment : current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw failure("CATALOG_PATH_UNSAFE",
                        "command catalog path must not contain symbolic links");
            }
        }
    }

    private List<Path> commandFiles(Path root) {
        try (Stream<Path> paths = Files.walk(
                root, MAX_SCAN_DEPTH, new FileVisitOption[0])) {
            List<Path> files = new ArrayList<Path>();
            paths.forEach(path -> {
                if (Files.isSymbolicLink(path)) {
                    throw failure("CATALOG_PATH_UNSAFE",
                            "command catalog must not contain symbolic links");
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && path.getFileName().toString().endsWith(".md")) {
                    files.add(path);
                }
            });
            files.sort(Comparator.comparing(Path::toString));
            return files;
        } catch (CapabilityCatalogException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new CapabilityCatalogException("CATALOG_READ_FAILED",
                    "cannot scan command catalog", failure);
        }
    }

    private CommandDefinition parseCommand(
            CommandCatalogDirectory directory, Path root, Path commandFile) {
        byte[] bytes = readCommandFile(root, commandFile);
        String markdown = new String(bytes, StandardCharsets.UTF_8);
        CommandFileParts parts = CommandFileParts.parse(markdown, commandFile);
        CatalogYaml frontmatter = CatalogYaml.parse(
                parts.frontmatter.getBytes(StandardCharsets.UTF_8), commandFile.toString());
        frontmatter.requireOnlyKeys(
                "identifier", "version", "displayName", "description", "argumentHint");
        return CommandDefinition.create(
                frontmatter.requiredString("identifier"),
                frontmatter.requiredString("version"),
                frontmatter.requiredString("displayName"),
                frontmatter.requiredString("description"),
                frontmatter.optionalString("argumentHint"), parts.promptTemplate,
                directory.getDirectoryIdentifier(), clock.instant());
    }

    private byte[] readCommandFile(Path root, Path commandFile) {
        try {
            if (Files.isSymbolicLink(commandFile)) {
                throw failure("CATALOG_PATH_UNSAFE",
                        "command file must not be a symbolic link");
            }
            Path real = commandFile.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)
                    || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("CATALOG_PATH_UNSAFE",
                        "command file escapes configured directory");
            }
            long size = Files.size(real);
            if (size > MAX_COMMAND_FILE_BYTES) {
                throw failure("CATALOG_FILE_TOO_LARGE",
                        "command file exceeds the allowed size");
            }
            return Files.readAllBytes(real);
        } catch (CapabilityCatalogException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new CapabilityCatalogException("CATALOG_READ_FAILED",
                    "cannot read command file", failure);
        }
    }

    private void rejectDuplicate(
            CommandDefinition existing, CommandDefinition candidate) {
        if (!existing.getContentHash().equals(candidate.getContentHash())) {
            throw failure("CATALOG_COMMAND_CONTENT_CONFLICT",
                    "same command identifier and version have different content");
        }
    }

    private static CapabilityCatalogException failure(String code, String message) {
        return new CapabilityCatalogException(code, message);
    }

    private static final class CommandFileParts {

        private final String frontmatter;
        private final String promptTemplate;

        private CommandFileParts(String frontmatter, String promptTemplate) {
            this.frontmatter = frontmatter;
            this.promptTemplate = promptTemplate;
        }

        private static CommandFileParts parse(String markdown, Path source) {
            String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
            if (!normalized.startsWith("---\n")) {
                throw failure("CATALOG_COMMAND_FORMAT_INVALID",
                        "command must start with YAML frontmatter: " + source.getFileName());
            }
            int closing = normalized.indexOf("\n---\n", 4);
            if (closing < 0) {
                throw failure("CATALOG_COMMAND_FORMAT_INVALID",
                        "command frontmatter is not closed: " + source.getFileName());
            }
            String frontmatter = normalized.substring(4, closing);
            String prompt = normalized.substring(closing + 5).trim();
            if (prompt.isEmpty()) {
                throw failure("CATALOG_COMMAND_FORMAT_INVALID",
                        "command prompt must not be blank: " + source.getFileName());
            }
            return new CommandFileParts(frontmatter, prompt);
        }
    }
}
