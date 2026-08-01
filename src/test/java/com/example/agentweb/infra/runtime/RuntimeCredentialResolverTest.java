package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.CredentialReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Credential Reference 解析、最小环境和 Secret 生命周期契约。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeCredentialResolverTest {

    private static final String SECRET = "provider-secret-must-not-leak";

    @TempDir
    Path tempDir;

    @Test
    void resolvesExplicitReferenceIntoProviderVariableAndCopiesOnlyAllowedEnvironment()
            throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary"));
        Path isolatedHome = Files.createDirectories(tempDir.resolve("runtime/home"));
        Map<String, String> source = new HashMap<String, String>();
        source.put("CODEX_SERVICE_KEY", SECRET);
        source.put("TEST_ALLOWED", "allowed-value");
        source.put("TEST_NOT_ALLOWED", "must-not-be-copied");
        RuntimeCredentialResolver resolver = new RuntimeCredentialResolver(
                () -> null, source::get);
        AgentExecutionPlan plan = RuntimePlanFixtures.readOnly("exec-credential", primary,
                Collections.singletonList(primary), Collections.singleton("TEST_ALLOWED"),
                CredentialReference.environment("CODEX_SERVICE_KEY"));
        Map<String, String> target = new HashMap<String, String>();
        target.put("PREEXISTING", "must-be-cleared");

        RuntimeCredentialResolver.ResolvedCredential credential =
                resolver.prepareEnvironment(plan.getRuntimeSelection(), plan.getRuntimeLimits(),
                        isolatedHome, target);

        assertEquals("allowed-value", target.get("TEST_ALLOWED"));
        assertEquals(SECRET, target.get("OPENAI_API_KEY"));
        assertEquals(isolatedHome.toString(), target.get("HOME"));
        assertEquals(isolatedHome.toString(), target.get("CODEX_HOME"));
        assertEquals(isolatedHome.toString(), target.get("XDG_CONFIG_HOME"));
        assertFalse(target.containsKey("TEST_NOT_ALLOWED"));
        assertFalse(target.containsKey("PREEXISTING"));
        assertFalse(credential.toString().contains(SECRET));
        assertEquals("credential=[REDACTED]",
                credential.redact("credential=" + SECRET, new RuntimeOutputRedactor()));
    }

    @Test
    void rejectsImplicitProviderCredentialInheritanceAndClearsSecretOnClose()
            throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-safe"));
        Path isolatedHome = Files.createDirectories(tempDir.resolve("runtime-safe/home"));
        RuntimeCredentialResolver resolver = new RuntimeCredentialResolver(
                () -> SECRET, name -> SECRET);
        AgentExecutionPlan unsafe = RuntimePlanFixtures.readOnly("exec-unsafe", primary,
                Collections.singletonList(primary), Collections.singleton("OPENAI_API_KEY"),
                CredentialReference.systemConfiguration());

        assertThrows(IllegalStateException.class,
                () -> resolver.prepareEnvironment(unsafe.getRuntimeSelection(),
                        unsafe.getRuntimeLimits(), isolatedHome,
                        new HashMap<String, String>()));

        AgentExecutionPlan safe = RuntimePlanFixtures.readOnly("exec-clear", primary,
                Collections.singletonList(primary), Collections.<String>emptySet(),
                CredentialReference.systemConfiguration());
        RuntimeCredentialResolver.ResolvedCredential credential =
                resolver.prepareEnvironment(safe.getRuntimeSelection(), safe.getRuntimeLimits(),
                        isolatedHome, new HashMap<String, String>());

        credential.close();

        assertTrue(credential.isCleared());
        assertThrows(IllegalStateException.class,
                () -> credential.redact(SECRET, new RuntimeOutputRedactor()));
    }

    @Test
    void failsClosedWhenExplicitCredentialReferenceCannotBeResolved() throws Exception {
        Path primary = Files.createDirectory(tempDir.resolve("primary-missing"));
        Path isolatedHome = Files.createDirectories(tempDir.resolve("runtime-missing/home"));
        RuntimeCredentialResolver resolver = new RuntimeCredentialResolver(
                () -> null, name -> null);
        AgentExecutionPlan plan = RuntimePlanFixtures.readOnly("exec-missing", primary,
                Collections.singletonList(primary), Collections.<String>emptySet(),
                CredentialReference.environment("MISSING_KEY"));

        assertThrows(IllegalStateException.class,
                () -> resolver.prepareEnvironment(plan.getRuntimeSelection(),
                        plan.getRuntimeLimits(), isolatedHome,
                        new HashMap<String, String>()));
    }
}
