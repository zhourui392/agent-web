package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.CredentialReference;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.domain.shared.AgentType;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 在进程启动边界解析 Credential Reference，并以受控对象封装 Secret 生命周期。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeCredentialResolver {

    private static final String CODEX_CREDENTIAL_VARIABLE = "OPENAI_API_KEY";
    private static final String CLAUDE_CREDENTIAL_VARIABLE = "ANTHROPIC_API_KEY";

    private final SystemCredentialSource systemCredentialSource;
    private final EnvironmentSource environmentSource;

    public RuntimeCredentialResolver(SystemCredentialSource systemCredentialSource,
                                     EnvironmentSource environmentSource) {
        this.systemCredentialSource = Objects.requireNonNull(
                systemCredentialSource, "systemCredentialSource");
        this.environmentSource = Objects.requireNonNull(environmentSource, "environmentSource");
    }

    public ResolvedCredential prepareEnvironment(RuntimeSelection selection,
                                                 RuntimeLimits limits,
                                                 Path isolatedHome,
                                                 Map<String, String> targetEnvironment) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(isolatedHome, "isolatedHome");
        Objects.requireNonNull(targetEnvironment, "targetEnvironment");
        ResolvedCredential credential = resolve(
                selection.getAgentType(), selection.getCredentialReference());
        try {
            targetEnvironment.clear();
            for (String name : limits.getEnvironmentAllowlist()) {
                requireNotProviderCredential(name);
                String value = environmentSource.resolve(name);
                if (value != null) {
                    targetEnvironment.put(name, value);
                }
            }
            String home = isolatedHome.toAbsolutePath().normalize().toString();
            targetEnvironment.put("HOME", home);
            targetEnvironment.put("XDG_CONFIG_HOME", home);
            if (selection.getAgentType() == AgentType.CODEX) {
                targetEnvironment.put("CODEX_HOME", home);
            }
            credential.applyTo(targetEnvironment);
            return credential;
        } catch (RuntimeException ex) {
            credential.close();
            throw ex;
        }
    }

    public ResolvedCredential resolve(AgentType agentType,
                                      CredentialReference reference) {
        Objects.requireNonNull(agentType, "agentType");
        Objects.requireNonNull(reference, "reference");
        String targetVariable = credentialVariable(agentType);
        String value;
        if (reference.getSource() == CredentialReference.Source.SYSTEM_CONFIGURATION) {
            value = systemCredentialSource.resolve();
            if (value == null || value.isEmpty()) {
                return ResolvedCredential.empty(targetVariable);
            }
        } else if (reference.getSource() == CredentialReference.Source.ENVIRONMENT) {
            String sourceVariable = reference.environmentVariable()
                    .orElseThrow(() -> new IllegalStateException(
                            "credential environment reference is missing"));
            value = environmentSource.resolve(sourceVariable);
            if (value == null || value.isEmpty()) {
                throw new IllegalStateException("credential reference could not be resolved");
            }
        } else {
            throw new IllegalStateException("unsupported Runtime credential source");
        }
        return new ResolvedCredential(targetVariable, value.toCharArray());
    }

    private String credentialVariable(AgentType agentType) {
        if (agentType == AgentType.CODEX) {
            return CODEX_CREDENTIAL_VARIABLE;
        }
        if (agentType == AgentType.CLAUDE) {
            return CLAUDE_CREDENTIAL_VARIABLE;
        }
        throw new IllegalStateException("in-process Agent does not use process credentials");
    }

    private void requireNotProviderCredential(String name) {
        if (CODEX_CREDENTIAL_VARIABLE.equals(name)
                || CLAUDE_CREDENTIAL_VARIABLE.equals(name)) {
            throw new IllegalStateException(
                    "provider credential must use an explicit credential reference");
        }
    }

    /**
     * 服务端受管 Credential 的启动期来源。
     */
    @FunctionalInterface
    public interface SystemCredentialSource {

        String resolve();
    }

    /**
     * 受控环境变量读取边界。
     */
    @FunctionalInterface
    public interface EnvironmentSource {

        String resolve(String name);
    }

    /**
     * 不公开 Secret getter 的短生命周期凭据；只允许注入、脱敏与清零。
     */
    public static final class ResolvedCredential implements AutoCloseable {

        private final String targetEnvironmentVariable;
        private char[] secret;
        private boolean cleared;

        private ResolvedCredential(String targetEnvironmentVariable, char[] secret) {
            this.targetEnvironmentVariable = targetEnvironmentVariable;
            this.secret = Arrays.copyOf(secret, secret.length);
        }

        private static ResolvedCredential empty(String targetEnvironmentVariable) {
            return new ResolvedCredential(targetEnvironmentVariable, new char[0]);
        }

        private synchronized void applyTo(Map<String, String> environment) {
            requireOpen();
            if (secret.length > 0) {
                environment.put(targetEnvironmentVariable, new String(secret));
            }
        }

        public synchronized String redact(String value, RuntimeOutputRedactor redactor) {
            requireOpen();
            if (secret.length == 0) {
                return redactor.redactSecrets(value, Collections.<String>emptyList());
            }
            return redactor.redactSecrets(value,
                    Collections.singletonList(new String(secret)));
        }

        public synchronized boolean isCleared() {
            return cleared;
        }

        @Override
        public synchronized void close() {
            if (cleared) {
                return;
            }
            Arrays.fill(secret, '\0');
            secret = new char[0];
            cleared = true;
        }

        @Override
        public String toString() {
            return "ResolvedCredential{targetEnvironmentVariable='"
                    + targetEnvironmentVariable + "', secret=[PROTECTED]}";
        }

        private void requireOpen() {
            if (cleared) {
                throw new IllegalStateException("resolved credential has been cleared");
            }
        }
    }
}
