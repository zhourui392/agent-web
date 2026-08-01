package com.example.agentweb.app.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.RunOrigin;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 在装配期验证 RunOrigin 与 ExecutionPlanProvider 的一一映射。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class ExecutionPlanProviderRegistry {

    private final Map<RunOrigin, ExecutionPlanProvider> providers;

    public ExecutionPlanProviderRegistry(List<ExecutionPlanProvider> providers) {
        if (providers == null) {
            throw new IllegalArgumentException("execution plan providers must not be null");
        }
        for (ExecutionPlanProvider provider : providers) {
            if (provider == null) {
                throw new IllegalArgumentException(
                        "execution plan providers must not contain null");
            }
            int supportedOrigins = supportedOriginCount(provider);
            if (supportedOrigins != 1) {
                throw new IllegalStateException(
                        "each execution plan provider must support exactly one origin, but found "
                                + supportedOrigins);
            }
        }
        EnumMap<RunOrigin, ExecutionPlanProvider> mapped =
                new EnumMap<RunOrigin, ExecutionPlanProvider>(RunOrigin.class);
        for (RunOrigin origin : RunOrigin.values()) {
            ExecutionPlanProvider matched = null;
            int matches = 0;
            for (ExecutionPlanProvider provider : providers) {
                if (provider.supports(origin)) {
                    matched = provider;
                    matches++;
                }
            }
            if (matches != 1) {
                throw new IllegalStateException(
                        "execution plan origin " + origin.name()
                                + " must have exactly one provider, but found " + matches);
            }
            mapped.put(origin, matched);
        }
        this.providers = mapped;
    }

    public AgentExecutionPlan prepare(ChatRun run) {
        if (run == null) {
            throw new IllegalArgumentException("chat run must not be null");
        }
        return providers.get(run.getRunOrigin()).prepare(run);
    }

    private int supportedOriginCount(ExecutionPlanProvider provider) {
        int count = 0;
        for (RunOrigin origin : RunOrigin.values()) {
            if (provider.supports(origin)) {
                count++;
            }
        }
        return count;
    }
}
