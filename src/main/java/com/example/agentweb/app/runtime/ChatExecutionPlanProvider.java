package com.example.agentweb.app.runtime;

import com.example.agentweb.app.chatrun.ChatRunExecutionContext;
import com.example.agentweb.app.chatrun.ChatRunPromptBuilder;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.PreparedChatRunPrompt;
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
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;

import java.util.Collections;
import java.util.Objects;

/**
 * 从普通 Chat 的持久化执行投影组装公共 Runtime 执行计划。
 *
 * <p>当前公共进程 Runtime 只支持 Codex、Prompt 前缀历史且尚未承载 recall/resume；
 * 对这些不能无损迁移的输入统一 fail-closed。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public final class ChatExecutionPlanProvider implements ExecutionPlanProvider {

    private final ChatRunQueryService queryService;
    private final ChatRunPromptBuilder promptBuilder;
    private final ResolvedCapabilityBinding capabilityBinding;
    private final RuntimeLimits runtimeLimits;

    public ChatExecutionPlanProvider(
            ChatRunQueryService queryService,
            ChatRunPromptBuilder promptBuilder,
            ResolvedCapabilityBinding capabilityBinding,
            RuntimeLimits runtimeLimits) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.capabilityBinding = Objects.requireNonNull(
                capabilityBinding, "capabilityBinding");
        this.runtimeLimits = Objects.requireNonNull(runtimeLimits, "runtimeLimits");
    }

    @Override
    public boolean supports(RunOrigin origin) {
        return origin == RunOrigin.CHAT;
    }

    @Override
    public AgentExecutionPlan prepare(ChatRun run) {
        ChatRun requiredRun = Objects.requireNonNull(run, "run");
        requiredRun.requireOrdinaryChat();
        ChatRunExecutionContext context = queryService.findExecutionContext(
                        requiredRun.getId().getValue())
                .orElseThrow(() -> new IllegalStateException(
                        "persisted Chat execution context is unavailable"));
        requiredRun.requireExactOrdinaryExecutionContext(
                context.getRunId(), context.getSessionId(),
                context.getUserMessageId(), context.isRecallEnabled());
        requireSupportedContext(requiredRun, context);

        PreparedChatRunPrompt prepared = Objects.requireNonNull(
                promptBuilder.prepareDetailed(context, context.getMessage()),
                "prepared Chat Runtime prompt");
        String prompt = prepared.getPrompt();
        String workspaceRoot = context.getWorkingDir();
        return new AgentExecutionPlan(
                new ExecutionIdentity(
                        requiredRun.getId().getValue(), context.getUserId(),
                        "chat:" + requiredRun.getSessionId()),
                new RuntimeSelection(
                        AgentType.CODEX, RuntimeVersionPolicy.configured()),
                new PromptPayload(
                        prompt, CanonicalHashing.sha256(prompt),
                        HistoryDelivery.PROMPT_PREFIX),
                new WorkspaceLayout(
                        workspaceRoot, Collections.singletonList(workspaceRoot),
                        Collections.singletonList(workspaceRoot),
                        SandboxMode.WORKSPACE_WRITE),
                capabilityBinding, runtimeLimits);
    }

    private void requireSupportedContext(
            ChatRun run, ChatRunExecutionContext context) {
        if (context.getAgentType() != AgentType.CODEX) {
            throw new IllegalStateException(
                    "common Chat Runtime currently supports Codex only");
        }
        if (run.isRecallEnabled()) {
            throw new IllegalStateException(
                    "common Chat Runtime does not yet support recall");
        }
        if (context.getResumeId() != null
                && !context.getResumeId().trim().isEmpty()) {
            throw new IllegalStateException(
                    "common Chat Runtime does not yet support provider resume");
        }
    }
}
