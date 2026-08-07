package com.example.agentweb.infra.runtime.profile;

import com.example.agentweb.app.runtime.port.AgentRuntimeSurface;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.RunMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Runtime Profile local-file loading and optional CLI credential tests.
 *
 * @author alex
 * @since 2026-08-07
 */
class AgentRuntimeProfileFileLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadProfileAndKeepOptionalCliKeyAbsent() throws Exception {
        Path file = tempDir.resolve("secrets.properties");
        Files.writeString(file, "agent.runtime.profiles.claude.agent-type=CLAUDE\n"
                + "agent.runtime.profiles.claude.endpoint=https://api.example\n"
                + "agent.runtime.profiles.claude.default-model=claude\n"
                + "agent.runtime.profiles.claude.default-reasoning-effort=medium\n"
                + "agent.runtime.profiles.claude.supported-surfaces=CHAT\n"
                + "agent.runtime.profiles.claude.supported-run-modes=DISCUSS_READ_ONLY\n");
        try {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows has no POSIX permission model.
        }

        AgentRuntimeProfile profile = AgentRuntimeProfileFileLoader.load(file)
                .select(AgentType.CLAUDE, AgentRuntimeSurface.CHAT,
                        RunMode.DISCUSS_READ_ONLY, null, null, null);

        assertEquals("claude", profile.getDefaultModel());
        assertNull(profile.getApiKey());
    }
}
