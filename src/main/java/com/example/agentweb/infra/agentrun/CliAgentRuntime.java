package com.example.agentweb.infra.agentrun;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.app.agentrun.port.AgentRunInvocation;
import com.example.agentweb.app.agentrun.port.AgentStreamResult;
import com.example.agentweb.app.agentrun.port.AgentRuntime;
import com.example.agentweb.app.agentrun.port.HistoryDeliveryMode;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.config.EnvProperties;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentCliProperties;
import com.example.agentweb.infra.ProcessEnvironmentSanitizer;
import com.example.agentweb.infra.cli.CliDialect;
import com.example.agentweb.infra.git.GitProcessEnvCustomizer;
import com.example.agentweb.infra.runtime.AgentProcessKernel;
import com.example.agentweb.infra.runtime.profile.AgentRuntimeProfile;
import com.example.agentweb.infra.runtime.profile.AgentRuntimeProfileCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Provider Runtime for Codex and Claude CLI.
 *
 * <p>The public Runtime path owns provider-neutral plans and handles. The
 * {@link AgentGateway} implementation in this class is an explicit rollback
 * port for scheduled jobs and the disabled common-runtime Chat switch; it is
 * not part of {@link AgentRuntime}.</p>
 *
 * @author alex
 * @since 2026-07-29
 */
@Component
public class CliAgentRuntime implements AgentRuntime, AgentGateway {

    private final AgentCliProperties cliProperties;
    private final EnvProperties envProperties;
    private final GitProcessEnvCustomizer gitEnvCustomizer;
    private final ProcessEnvironmentSanitizer environmentSanitizer;
    private final AgentProcessKernel kernel;
    private final AgentRuntimeProfileCatalog profiles;
    private final Map<AgentType, CliDialect> dialects;

    @Autowired
    public CliAgentRuntime(AgentCliProperties cliProperties,
                           EnvProperties envProperties,
                           GitProcessEnvCustomizer gitEnvCustomizer,
                           ProcessEnvironmentSanitizer environmentSanitizer,
                           AgentProcessKernel kernel,
                           AgentRuntimeProfileCatalog profiles,
                           List<CliDialect> dialectBeans) {
        this(cliProperties, envProperties, gitEnvCustomizer, environmentSanitizer,
                kernel, profiles, dialectBeans, false);
    }

    /** Compatibility construction path retained for isolated legacy tests. */
    public CliAgentRuntime(AgentCliProperties cliProperties,
                           EnvProperties envProperties,
                           GitProcessEnvCustomizer gitEnvCustomizer,
                           List<CliDialect> dialectBeans) {
        this(cliProperties, envProperties, gitEnvCustomizer,
                new ProcessEnvironmentSanitizer(),
                AgentProcessKernel.compatibilityKernel(), null,
                dialectBeans, true);
    }

    private CliAgentRuntime(AgentCliProperties cliProperties,
                            EnvProperties envProperties,
                            GitProcessEnvCustomizer gitEnvCustomizer,
                            ProcessEnvironmentSanitizer environmentSanitizer,
                            AgentProcessKernel kernel,
                            AgentRuntimeProfileCatalog profiles,
                            List<CliDialect> dialectBeans,
                            boolean compatibilityConstruction) {
        this.cliProperties = Objects.requireNonNull(cliProperties, "CLI properties");
        this.envProperties = Objects.requireNonNull(envProperties, "environment properties");
        this.gitEnvCustomizer = Objects.requireNonNull(
                gitEnvCustomizer, "git environment customizer");
        this.environmentSanitizer = Objects.requireNonNull(
                environmentSanitizer, "process environment sanitizer");
        this.kernel = Objects.requireNonNull(kernel, "process kernel");
        this.profiles = profiles;
        this.dialects = dialectIndex(dialectBeans);
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
        return result;
    }

    @Override
    public Set<AgentType> supportedTypes() {
        return EnumSet.of(AgentType.CODEX, AgentType.CLAUDE);
    }

    @Override
    public RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink) {
        if (profiles == null) {
            throw new IllegalStateException("common CLI Runtime is not configured");
        }
        AgentType type = plan.getRuntimeSelection().getAgentType();
        AgentRuntimeProfile profile = profiles.byId(
                plan.getRuntimeSelection().getProfileId());
        if (profile == null || profile.getAgentType() != type || !profile.isEnabled()) {
            throw new IllegalStateException("runtime profile is unavailable: "
                    + plan.getRuntimeSelection().getProfileId());
        }
        CliDialect dialect = resolveDialect(type);
        AgentCliProperties.Client client = resolveClient(type);
        return kernel.start(plan, sink, dialect, client, profile.getApiKey());
    }

    @Override
    public void requestStop(RuntimeHandle handle) {
        kernel.requestStop(handle);
    }

    @Override
    public RuntimeObservation observe(RuntimeHandle handle) {
        return kernel.observe(handle);
    }

    // ========== Explicit legacy AgentGateway compatibility ================

    @Override
    public void runStream(AgentType type, String workingDir, String userMessage,
                          String sessionId, String resumeId, String env,
                          long timeoutSeconds, Consumer<String> onChunk,
                          java.util.function.IntConsumer onExit, String userId,
                          Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        runStreamInternal(type, workingDir, userMessage, sessionId, resumeId, env,
                timeoutSeconds, onChunk,
                result -> onExit.accept(result.getExitCode()), userId, extraEnv);
    }

    @Override
    public void runStreamWithResult(AgentType type, String workingDir, String userMessage,
                                    String sessionId, String resumeId, String env,
                                    long timeoutSeconds, Consumer<String> onChunk,
                                    Consumer<AgentStreamResult> onExit, String userId,
                                    Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        runStreamInternal(type, workingDir, userMessage, sessionId, resumeId, env,
                timeoutSeconds, onChunk, onExit, userId, extraEnv);
    }

    private void runStreamInternal(AgentType type, String workingDir, String userMessage,
                                   String sessionId, String resumeId, String env,
                                   long timeoutSeconds, Consumer<String> onChunk,
                                   Consumer<AgentStreamResult> onExit, String userId,
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
        kernel.runLegacy(invocation, resolveClient(type), dialect,
                composeMessage(userMessage, env), sanitizedEnvironment(userId, extraEnv),
                onChunk, onExit);
    }

    @Override
    public void stopStream(String sessionId) {
        kernel.stopLegacy(sessionId);
    }

    public boolean isRunning(String sessionId) {
        return kernel.isLegacyRunning(sessionId);
    }

    @Override
    public String extractResumeId(AgentType type, String rawLine) {
        return resolveDialect(type).extractResumeId(rawLine);
    }

    @Override
    public List<String> normalizeChunk(AgentType type, String rawLine) {
        return resolveDialect(type).normalizeChunk(rawLine);
    }

    @Override
    public HistoryDeliveryMode historyDeliveryMode(AgentType type) {
        return HistoryDeliveryMode.PROMPT_PREFIX;
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

    private String composeMessage(String userMessage, String env) {
        String envPrefix = resolveEnvPrefix(env);
        if (userMessage != null && userMessage.startsWith("/")) {
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

    private CliDialect resolveDialect(AgentType type) {
        CliDialect dialect = dialects.get(type);
        if (dialect == null) {
            throw new IllegalArgumentException("No CliDialect registered for type: " + type);
        }
        return dialect;
    }

    private AgentCliProperties.Client resolveClient(AgentType type) {
        if (type == AgentType.CODEX) {
            return cliProperties.getCodex();
        }
        if (type == AgentType.CLAUDE) {
            return cliProperties.getClaude();
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }
}
