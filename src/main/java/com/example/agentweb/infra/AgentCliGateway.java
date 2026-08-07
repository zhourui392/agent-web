package com.example.agentweb.infra;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.app.agentrun.port.AgentRunInvocation;
import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.config.EnvProperties;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.cli.CliDialect;
import com.example.agentweb.infra.git.GitProcessEnvCustomizer;
import com.example.agentweb.infra.runtime.AgentProcessKernel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Compatibility facade for the legacy CLI gateway.
 *
 * <p>The facade still exposes the historical {@link AgentGateway} signatures, but it no longer
 * owns a {@code ProcessBuilder}, watchdog, stdout reader, or process registry. Those concerns
 * are delegated to {@link AgentProcessKernel}; this class only resolves the legacy environment
 * prefix and constructs the compatibility invocation.</p>
 *
 * @author alex
 * @since 2026-08-07
 */
@Component
@Slf4j
public class AgentCliGateway implements AgentGateway {

    private static final String SLASH_PREFIX = "/";

    private final AgentCliProperties props;
    private final EnvProperties envProperties;
    private final GitProcessEnvCustomizer gitEnvCustomizer;
    private final ProcessEnvironmentSanitizer environmentSanitizer;
    private final Map<AgentType, CliDialect> dialects;
    private final AgentProcessKernel kernel;

    @Autowired
    public AgentCliGateway(AgentCliProperties props,
                           EnvProperties envProperties,
                           GitProcessEnvCustomizer gitEnvCustomizer,
                           List<CliDialect> dialectBeans,
                           ProcessEnvironmentSanitizer environmentSanitizer,
                           AgentProcessKernel kernel) {
        this.props = Objects.requireNonNull(props, "CLI properties");
        this.envProperties = Objects.requireNonNull(envProperties, "environment properties");
        this.gitEnvCustomizer = Objects.requireNonNull(
                gitEnvCustomizer, "git environment customizer");
        this.environmentSanitizer = Objects.requireNonNull(
                environmentSanitizer, "process environment sanitizer");
        this.dialects = dialectIndex(dialectBeans);
        this.kernel = Objects.requireNonNull(kernel, "process kernel");
    }

    /** Compatibility construction path retained for process-isolated unit tests. */
    AgentCliGateway(AgentCliProperties props,
                    EnvProperties envProperties,
                    GitProcessEnvCustomizer gitEnvCustomizer,
                    List<CliDialect> dialectBeans) {
        this(props, envProperties, gitEnvCustomizer, dialectBeans,
                new ProcessEnvironmentSanitizer(), AgentProcessKernel.compatibilityKernel());
    }

    private Map<AgentType, CliDialect> dialectIndex(List<CliDialect> dialectBeans) {
        Map<AgentType, CliDialect> result = new EnumMap<AgentType, CliDialect>(AgentType.class);
        if (dialectBeans != null) {
            for (CliDialect dialect : dialectBeans) {
                if (dialect != null) {
                    result.put(dialect.type(), dialect);
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void runStream(AgentType type,
                          String workingDir,
                          String userMessage,
                          String sessionId,
                          String resumeId,
                          String env,
                          long timeoutSeconds,
                          Consumer<String> onChunk,
                          java.util.function.IntConsumer onExit,
                          String userId,
                          Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        runStreamInternal(type, workingDir, userMessage, sessionId, resumeId, env,
                timeoutSeconds, onChunk,
                result -> onExit.accept(result.getExitCode()), userId, extraEnv);
    }

    @Override
    public void runStreamWithResult(AgentType type,
                                    String workingDir,
                                    String userMessage,
                                    String sessionId,
                                    String resumeId,
                                    String env,
                                    long timeoutSeconds,
                                    Consumer<String> onChunk,
                                    Consumer<AgentStreamResult> onExit,
                                    String userId,
                                    Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        runStreamInternal(type, workingDir, userMessage, sessionId, resumeId, env,
                timeoutSeconds, onChunk, onExit, userId, extraEnv);
    }

    private void runStreamInternal(AgentType type,
                                   String workingDir,
                                   String userMessage,
                                   String sessionId,
                                   String resumeId,
                                   String env,
                                   long timeoutSeconds,
                                   Consumer<String> onChunk,
                                   Consumer<AgentStreamResult> onExit,
                                   String userId,
                                   Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        CliDialect dialect = resolveDialect(type);
        AgentRunInvocation invocation = AgentRunInvocation.builder()
                .runId(sessionId == null ? "legacy-run" : sessionId)
                .conversationId(sessionId == null ? "legacy-conversation" : sessionId)
                .userMessageId(0L)
                .agentType(type)
                .workingDir(workingDir)
                .prompt(userMessage == null ? "" : userMessage)
                .resumeId(resumeId)
                .env(env)
                .userId(userId)
                .timeoutSeconds(timeoutSeconds)
                .history(Collections.emptyList())
                .extraEnv(extraEnv)
                .build();
        String stdinMessage = composeMessage(userMessage, env);
        kernel.runLegacy(invocation, resolve(type), dialect, stdinMessage,
                sanitizedEnvironment(userId, extraEnv), onChunk, onExit);
    }

    private Map<String, String> sanitizedEnvironment(String userId,
                                                      Map<String, String> extraEnv) {
        Map<String, String> environment = new HashMap<String, String>(System.getenv());
        environmentSanitizer.sanitize(environment);
        gitEnvCustomizer.applyIdentityOnly(environment, userId);
        if (extraEnv != null && !extraEnv.isEmpty()) {
            environment.putAll(extraEnv);
        }
        return environment;
    }

    /** Builds the historical environment-prefix message without leaking it into the Runtime plan. */
    private String composeMessage(String userMessage, String env) {
        String envPrefix = resolveEnvPrefix(env);
        if (userMessage != null && userMessage.startsWith(SLASH_PREFIX)) {
            return envPrefix.isEmpty() ? userMessage : userMessage + "\n\n" + envPrefix;
        }
        return envPrefix + (userMessage == null ? "" : userMessage);
    }

    private String resolveEnvPrefix(String env) {
        if (env == null || env.trim().isEmpty()) {
            return "";
        }
        EnvProperties.EnvEntry entry = envProperties.findByKey(env.trim());
        return entry != null && entry.getPrompt() != null ? entry.getPrompt() : "";
    }

    @Override
    public void stopStream(String sessionId) {
        kernel.stopLegacy(sessionId);
    }

    boolean isRunning(String sessionId) {
        return kernel.isLegacyRunning(sessionId);
    }

    @Override
    public String extractResumeId(AgentType type, String stdoutLine) {
        return resolveDialect(type).extractResumeId(stdoutLine);
    }

    @Override
    public List<String> normalizeChunk(AgentType type, String stdoutLine) {
        return resolveDialect(type).normalizeChunk(stdoutLine);
    }

    private CliDialect resolveDialect(AgentType type) {
        CliDialect dialect = dialects.get(type);
        if (dialect == null) {
            throw new IllegalArgumentException("No CliDialect registered for type: " + type);
        }
        return dialect;
    }

    private AgentCliProperties.Client resolve(AgentType type) {
        if (type == AgentType.CODEX) {
            return props.getCodex();
        }
        if (type == AgentType.CLAUDE) {
            return props.getClaude();
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }
}
