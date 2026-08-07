package com.example.agentweb.infra.runtime.profile;

import com.example.agentweb.app.runtime.port.AgentRuntimeSurface;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.RunMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Loads Runtime Profiles from the Git-ignored data/secrets.properties file.
 *
 * @author alex
 * @since 2026-08-07
 */
public final class AgentRuntimeProfileFileLoader {

    private static final String PREFIX = "agent.runtime.profiles.";

    private AgentRuntimeProfileFileLoader() {
    }

    public static AgentRuntimeProfileCatalog load(Path file) {
        if (file == null || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new AgentRuntimeProfileCatalog(List.of());
        }
        if (Files.isSymbolicLink(file)) {
            throw new IllegalStateException("Runtime Profile file must not be a symbolic link");
        }
        validatePermissions(file);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException ex) {
            throw new IllegalStateException("could not load Runtime Profiles", ex);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(PREFIX)) {
                String remainder = key.substring(PREFIX.length());
                int separator = remainder.indexOf('.');
                if (separator > 0) {
                    ids.add(remainder.substring(0, separator));
                }
            }
        }
        List<AgentRuntimeProfile> profiles = new ArrayList<>();
        for (String id : ids) {
            profiles.add(profile(properties, id));
        }
        return new AgentRuntimeProfileCatalog(profiles);
    }

    private static AgentRuntimeProfile profile(Properties properties, String id) {
        String prefix = PREFIX + id + ".";
        AgentType agentType = AgentType.parseKnown(required(properties, prefix + "agent-type"));
        String endpoint = required(properties, prefix + "endpoint");
        String model = required(properties, prefix + "default-model");
        String reasoning = required(properties, prefix + "default-reasoning-effort");
        String surfaces = properties.getProperty(prefix + "supported-surfaces",
                agentType == AgentType.NATIVE ? "CHAT" : "CHAT,WORKBENCH");
        String modes = properties.getProperty(prefix + "supported-run-modes",
                "DISCUSS_READ_ONLY,MODIFY_WORKSPACE");
        return new AgentRuntimeProfile(id, agentType, endpoint,
                properties.getProperty(prefix + "api-key"), model,
                csv(properties.getProperty(prefix + "allowed-models"), model),
                reasoning,
                csv(properties.getProperty(prefix + "allowed-reasoning-efforts"), reasoning),
                properties.getProperty(prefix + "runtime-environment"),
                surfaces(surfaces), modes(modes),
                Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "true")));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing Runtime Profile property: " + key);
        }
        return value.trim();
    }

    private static Set<String> csv(String value, String fallback) {
        Set<String> result = new LinkedHashSet<>();
        String source = value == null || value.isBlank() ? fallback : value;
        for (String entry : source.split(",")) {
            if (!entry.isBlank()) {
                result.add(entry.trim());
            }
        }
        return result;
    }

    private static Set<AgentRuntimeSurface> surfaces(String value) {
        EnumSet<AgentRuntimeSurface> result = EnumSet.noneOf(AgentRuntimeSurface.class);
        for (String entry : value.split(",")) {
            result.add(AgentRuntimeSurface.valueOf(entry.trim().toUpperCase()));
        }
        return result;
    }

    private static Set<RunMode> modes(String value) {
        EnumSet<RunMode> result = EnumSet.noneOf(RunMode.class);
        for (String entry : value.split(",")) {
            result.add(RunMode.valueOf(entry.trim().toUpperCase()));
        }
        return result;
    }

    private static void validatePermissions(Path file) {
        try {
            if (hasGroupOrOtherPermissions(file)) {
                throw new IllegalStateException("Runtime Profile file permissions are too broad");
            }
            Path parent = file.toAbsolutePath().normalize().getParent();
            if (parent != null && hasGroupOrOtherPermissions(parent)) {
                throw new IllegalStateException("Runtime Profile directory permissions are too broad");
            }
        } catch (UnsupportedOperationException | IOException ex) {
            // Non-POSIX development hosts have no equivalent permission projection.
        }
    }

    private static boolean hasGroupOrOtherPermissions(Path path) throws IOException {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                path, LinkOption.NOFOLLOW_LINKS);
        return permissions.contains(PosixFilePermission.GROUP_READ)
                || permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                || permissions.contains(PosixFilePermission.OTHERS_READ)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
    }
}
