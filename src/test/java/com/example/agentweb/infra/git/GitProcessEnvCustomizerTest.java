package com.example.agentweb.infra.git;

import com.example.agentweb.app.git.GitEnvResolver;
import com.example.agentweb.app.git.GitEnvSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GitProcessEnvCustomizer} 注入分支单测：Mock resolver + askpass，真实 {@link ProcessBuilder}。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
class GitProcessEnvCustomizerTest {

    private GitEnvResolver resolver;
    private GitAskpassScript askpassScript;
    private GitProcessEnvCustomizer customizer;

    @BeforeEach
    void setUp() {
        resolver = mock(GitEnvResolver.class);
        askpassScript = mock(GitAskpassScript.class);
        customizer = new GitProcessEnvCustomizer(resolver, askpassScript);
    }

    @Test
    void empty_spec_should_not_touch_env_or_askpass() throws Exception {
        when(resolver.resolve("E10001")).thenReturn(GitEnvSpec.EMPTY);
        ProcessBuilder pb = new ProcessBuilder("git");

        customizer.apply(pb, "E10001");

        assertNull(pb.environment().get("GIT_AUTHOR_NAME"));
        assertNull(pb.environment().get("GIT_ASKPASS"));
        verify(askpassScript, never()).ensureScript();
    }

    @Test
    void identity_spec_should_inject_author_committer_env_only() throws Exception {
        when(resolver.resolve("u1")).thenReturn(new GitEnvSpec(identityEnv(), null, null));
        ProcessBuilder pb = new ProcessBuilder("git");

        customizer.apply(pb, "u1");

        Map<String, String> env = pb.environment();
        assertEquals("周锐", env.get("GIT_AUTHOR_NAME"));
        assertEquals("zhourui@x.com", env.get("GIT_AUTHOR_EMAIL"));
        assertEquals("周锐", env.get("GIT_COMMITTER_NAME"));
        assertEquals("zhourui@x.com", env.get("GIT_COMMITTER_EMAIL"));
        assertNull(env.get("GIT_ASKPASS"));
        verify(askpassScript, never()).ensureScript();
    }

    @Test
    void credential_spec_should_inject_askpass_and_credential_env() throws Exception {
        when(resolver.resolve("u1"))
                .thenReturn(new GitEnvSpec(identityEnv(), "gituser", "secret-token"));
        when(askpassScript.ensureScript()).thenReturn("/tmp/agent-web-git-askpass.sh");
        ProcessBuilder pb = new ProcessBuilder("git");

        customizer.apply(pb, "u1");

        Map<String, String> env = pb.environment();
        assertEquals("/tmp/agent-web-git-askpass.sh", env.get("GIT_ASKPASS"));
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
        assertEquals("gituser", env.get("AGENT_GIT_USERNAME"));
        assertEquals("secret-token", env.get("AGENT_GIT_PASSWORD"));
        assertEquals("周锐", env.get("GIT_AUTHOR_NAME"));
    }

    @Test
    void askpass_write_failure_should_degrade_to_identity_only() throws Exception {
        when(resolver.resolve("u1"))
                .thenReturn(new GitEnvSpec(identityEnv(), "gituser", "secret-token"));
        when(askpassScript.ensureScript()).thenThrow(new java.io.IOException("disk full"));
        ProcessBuilder pb = new ProcessBuilder("git");

        customizer.apply(pb, "u1");

        Map<String, String> env = pb.environment();
        assertNull(env.get("GIT_ASKPASS"));
        assertEquals("周锐", env.get("GIT_AUTHOR_NAME"));
    }

    private Map<String, String> identityEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_AUTHOR_NAME", "周锐");
        env.put("GIT_AUTHOR_EMAIL", "zhourui@x.com");
        env.put("GIT_COMMITTER_NAME", "周锐");
        env.put("GIT_COMMITTER_EMAIL", "zhourui@x.com");
        return env;
    }
}
