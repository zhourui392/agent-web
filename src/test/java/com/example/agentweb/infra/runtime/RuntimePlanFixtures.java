package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.ExecutionIdentity;
import com.example.agentweb.app.runtime.port.HistoryDelivery;
import com.example.agentweb.app.runtime.port.PromptPayload;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 公共 Runtime 基础设施测试使用的完整执行计划 Fixture。
 *
 * @author alex
 * @since 2026-08-01
 */
final class RuntimePlanFixtures {

    private RuntimePlanFixtures() {
    }

    static AgentExecutionPlan plan(String executionId, Path primary,
                                   List<Path> readable, List<Path> writable,
                                   SandboxMode sandboxMode, Duration timeout,
                                   long maximumOutputBytes) {
        String prompt = "perform the bounded runtime task";
        return new AgentExecutionPlan(
                new ExecutionIdentity(executionId, "owner-1", "workbench:wb-1"),
                new RuntimeSelection(AgentType.CODEX, RuntimeVersionPolicy.configured()),
                new PromptPayload(prompt, CanonicalHashing.sha256(prompt),
                        HistoryDelivery.PROMPT_PREFIX),
                new WorkspaceLayout(primary.toString(), strings(readable), strings(writable),
                        sandboxMode),
                emptyBinding(),
                new RuntimeLimits(timeout, maximumOutputBytes));
    }

    static AgentExecutionPlan readOnly(String executionId, Path primary,
                                       List<Path> readable) {
        return plan(executionId, primary, readable, Collections.<Path>emptyList(),
                SandboxMode.READ_ONLY, Duration.ofSeconds(5L), 1024L * 1024L);
    }

    private static List<String> strings(List<Path> paths) {
        java.util.ArrayList<String> values = new java.util.ArrayList<String>();
        for (Path path : paths) {
            values.add(path.toString());
        }
        return values;
    }

    private static ResolvedCapabilityBinding emptyBinding() {
        return ResolvedCapabilityBinding.resolve("policy@1", "default", "1.0.0",
                CanonicalHashing.sha256("profile"),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), "codex");
    }
}
