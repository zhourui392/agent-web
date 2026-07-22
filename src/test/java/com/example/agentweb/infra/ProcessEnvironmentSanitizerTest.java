package com.example.agentweb.infra;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link ProcessEnvironmentSanitizer} 子进程环境隔离测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class ProcessEnvironmentSanitizerTest {

    @Test
    void sanitize_should_keepRuntimeAndCliAuth_butRemoveServiceSecrets() {
        ProcessBuilder processBuilder = new ProcessBuilder("agent");
        Map<String, String> env = processBuilder.environment();
        env.clear();
        env.put("PATH", "/bin");
        env.put("HOME", "/home/user");
        env.put("CODEX_HOME", "/home/user/.codex");
        env.put("OPENAI_API_KEY", "agent-auth");
        env.put("FEISHU_APP_SECRET", "service-secret");
        env.put("REFINERY_EMBED_API_KEY", "embed-secret");
        env.put("AGENT_DB_PATH", "/secret/db");

        new ProcessEnvironmentSanitizer().sanitize(processBuilder);

        assertEquals("/bin", env.get("PATH"));
        assertEquals("/home/user/.codex", env.get("CODEX_HOME"));
        assertEquals("agent-auth", env.get("OPENAI_API_KEY"));
        assertFalse(env.containsKey("FEISHU_APP_SECRET"));
        assertFalse(env.containsKey("REFINERY_EMBED_API_KEY"));
        assertFalse(env.containsKey("AGENT_DB_PATH"));
    }
}
