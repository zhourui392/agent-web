package com.example.agentweb.infra.agentrun;

import com.example.agentweb.app.agentrun.port.AgentExecutionResult;
import com.example.agentweb.app.agentrun.port.AgentRunInvocation;
import com.example.agentweb.app.agentrun.port.AgentRuntime;
import com.example.agentweb.app.agentrun.port.HistoryDeliveryMode;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.AgentCliGateway;
import com.example.agentweb.infra.AgentCliProperties;
import com.example.agentweb.infra.cli.CliDialect;
import com.example.agentweb.infra.runtime.AgentProcessKernel;
import com.example.agentweb.infra.runtime.profile.AgentRuntimeProfile;
import com.example.agentweb.infra.runtime.profile.AgentRuntimeProfileCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.EnumMap;
import java.util.Map;

/**
 * Adapts the existing subprocess gateway to the structured runtime port.
 *
 * @author alex
 * @since 2026-07-29
 */
@Component
public class CliAgentRuntime implements AgentRuntime {

    private final AgentCliGateway cli;
    private final AgentProcessKernel kernel;
    private final AgentRuntimeProfileCatalog profiles;
    private final AgentCliProperties properties;
    private final Map<AgentType, CliDialect> dialects;

    @Autowired
    public CliAgentRuntime(AgentCliGateway cli, AgentProcessKernel kernel,
                           AgentRuntimeProfileCatalog profiles,
                           AgentCliProperties properties,
                           List<CliDialect> dialectBeans) {
        this.cli = cli;
        this.kernel = kernel;
        this.profiles = profiles;
        this.properties = properties;
        this.dialects = new EnumMap<AgentType, CliDialect>(AgentType.class);
        for (CliDialect dialect : dialectBeans) {
            this.dialects.put(dialect.type(), dialect);
        }
    }

    /** Legacy construction path retained for isolated tests and pre-migration callers. */
    public CliAgentRuntime(AgentCliGateway cli) {
        this.cli = cli;
        this.kernel = null;
        this.profiles = null;
        this.properties = null;
        this.dialects = new EnumMap<AgentType, CliDialect>(AgentType.class);
    }

    @Override
    public Set<AgentType> supportedTypes() {
        return EnumSet.of(AgentType.CODEX, AgentType.CLAUDE);
    }

    @Override
    public HistoryDeliveryMode historyDeliveryMode() {
        return HistoryDeliveryMode.PROMPT_PREFIX;
    }

    @Override
    public void run(AgentRunInvocation invocation, Consumer<String> onChunk,
                    Consumer<AgentExecutionResult> onComplete)
            throws IOException, InterruptedException {
        cli.runStreamWithResult(invocation.getAgentType(), invocation.getWorkingDir(),
                invocation.getPrompt(), invocation.getRunId(), invocation.getResumeId(),
                invocation.getEnv(), invocation.getTimeoutSeconds(), onChunk,
                result -> onComplete.accept(AgentExecutionResult.fromStream(result)),
                invocation.getUserId(), invocation.getExtraEnv());
    }

    @Override
    public void stop(String runId) {
        cli.stopStream(runId);
    }

    @Override
    public RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink) {
        if (kernel == null || profiles == null || properties == null) {
            throw new IllegalStateException("common CLI Runtime is not configured");
        }
        AgentType type = plan.getRuntimeSelection().getAgentType();
        AgentRuntimeProfile profile = profiles.byId(plan.getRuntimeSelection().getProfileId());
        if (profile == null || profile.getAgentType() != type || !profile.isEnabled()) {
            throw new IllegalStateException("runtime profile is unavailable: "
                    + plan.getRuntimeSelection().getProfileId());
        }
        CliDialect dialect = dialects.get(type);
        if (dialect == null) {
            throw new IllegalStateException("CLI dialect is unavailable: " + type);
        }
        AgentCliProperties.Client client = type == AgentType.CODEX
                ? properties.getCodex() : properties.getClaude();
        return kernel.start(plan, sink, dialect, client, profile.getApiKey());
    }

    @Override
    public void requestStop(RuntimeHandle handle) {
        if (kernel == null) {
            AgentRuntime.super.requestStop(handle);
            return;
        }
        kernel.requestStop(handle);
    }

    @Override
    public RuntimeObservation observe(RuntimeHandle handle) {
        return kernel == null ? AgentRuntime.super.observe(handle) : kernel.observe(handle);
    }

    @Override
    public String extractResumeId(AgentType type, String rawLine) {
        return cli.extractResumeId(type, rawLine);
    }

    @Override
    public List<String> normalizeChunk(AgentType type, String rawLine) {
        return cli.normalizeChunk(type, rawLine);
    }
}
