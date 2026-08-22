package com.example.agentweb.app.runtime;

import com.example.agentweb.app.chatrun.ChatRunExecutionContext;
import com.example.agentweb.app.chatrun.ChatRunPromptBuilder;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.PreparedChatRunPrompt;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.AgentRuntimeSurface;
import com.example.agentweb.app.agentrun.port.AgentHistoryMessage;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeSelectionStore;
import com.example.agentweb.app.runtime.port.ExecutionIdentity;
import com.example.agentweb.app.runtime.port.HistoryDelivery;
import com.example.agentweb.app.runtime.port.PromptPayload;
import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeProfileSelector;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.app.runtime.port.SandboxMode;
import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.RunMode;

import java.util.Collections;
import java.util.Objects;
import java.util.List;
import java.util.stream.Collectors;

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
    private final RuntimeProfileSelector profileSelector;
    private final ChatRunRuntimeSelectionStore selectionStore;
    private final com.example.agentweb.domain.mode.ChatModeCapabilityResolver modeCapabilityResolver;
    private final com.example.agentweb.app.mode.HandoffFilePort handoffFilePort;
    private final String runtimeCompatibility;

    public ChatExecutionPlanProvider(
            ChatRunQueryService queryService,
            ChatRunPromptBuilder promptBuilder,
            ResolvedCapabilityBinding capabilityBinding,
            RuntimeLimits runtimeLimits) {
        this(queryService, promptBuilder, capabilityBinding, runtimeLimits, null);
    }

    public ChatExecutionPlanProvider(
            ChatRunQueryService queryService,
            ChatRunPromptBuilder promptBuilder,
            ResolvedCapabilityBinding capabilityBinding,
            RuntimeLimits runtimeLimits,
            RuntimeProfileSelector profileSelector) {
        this(queryService, promptBuilder, capabilityBinding, runtimeLimits,
                profileSelector, null);
    }

    public ChatExecutionPlanProvider(
            ChatRunQueryService queryService,
            ChatRunPromptBuilder promptBuilder,
            ResolvedCapabilityBinding capabilityBinding,
            RuntimeLimits runtimeLimits,
            RuntimeProfileSelector profileSelector,
            ChatRunRuntimeSelectionStore selectionStore) {
        this(queryService, promptBuilder, capabilityBinding, runtimeLimits,
                profileSelector, selectionStore, null, null, null);
    }

    public ChatExecutionPlanProvider(
            ChatRunQueryService queryService,
            ChatRunPromptBuilder promptBuilder,
            ResolvedCapabilityBinding capabilityBinding,
            RuntimeLimits runtimeLimits,
            RuntimeProfileSelector profileSelector,
            ChatRunRuntimeSelectionStore selectionStore,
            com.example.agentweb.domain.mode.ChatModeCapabilityResolver modeCapabilityResolver,
            com.example.agentweb.app.mode.HandoffFilePort handoffFilePort,
            String runtimeCompatibility) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.capabilityBinding = Objects.requireNonNull(
                capabilityBinding, "capabilityBinding");
        this.runtimeLimits = Objects.requireNonNull(runtimeLimits, "runtimeLimits");
        this.profileSelector = profileSelector;
        this.selectionStore = selectionStore;
        this.modeCapabilityResolver = modeCapabilityResolver;
        this.handoffFilePort = handoffFilePort;
        this.runtimeCompatibility = runtimeCompatibility;
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

        // 模式会话：按冻结快照解析能力绑定与 Claude 下发事实；无模式走默认空绑定（零行为变化）
        com.example.agentweb.domain.mode.ModeSnapshot modeSnapshot = context.getModeSnapshot();
        ResolvedCapabilityBinding binding = this.capabilityBinding;
        com.example.agentweb.app.runtime.port.ModeRuntimeDelivery modeDelivery = null;
        com.example.agentweb.app.chatrun.ChatRunPromptExtras extras =
                com.example.agentweb.app.chatrun.ChatRunPromptExtras.none();
        if (modeSnapshot != null) {
            if (modeCapabilityResolver == null || runtimeCompatibility == null) {
                throw new IllegalStateException(
                        "chat mode runtime delivery is not configured");
            }
            com.example.agentweb.domain.mode.ResolvedModeCapabilities resolved =
                    modeCapabilityResolver.resolve(
                            modeSnapshot, context.getAgentType(), runtimeCompatibility);
            binding = resolved.getBinding();
            modeDelivery = new com.example.agentweb.app.runtime.port.ModeRuntimeDelivery(
                    modeSnapshot.getDefaultPrompt(), modeSnapshot.getPermissionMode(),
                    resolved.getCommands().stream()
                            .map(command -> new com.example.agentweb.app.runtime.port
                                    .ModeRuntimeDelivery.CommandPayload(
                                    command.getIdentifier(), command.getPromptTemplate()))
                            .collect(Collectors.toList()));
            extras = new com.example.agentweb.app.chatrun.ChatRunPromptExtras(
                    context.getHandoffFilePath(),
                    readHandoffContent(context),
                    capabilityAnnouncement(modeSnapshot, resolved));
        }

        PreparedChatRunPrompt prepared = Objects.requireNonNull(
                extras.isEmpty()
                        ? promptBuilder.prepareDetailed(context, context.getMessage())
                        : promptBuilder.prepareDetailed(context, context.getMessage(),
                        com.example.agentweb.app.agentrun.port.HistoryDeliveryMode.PROMPT_PREFIX,
                        extras),
                "prepared Chat Runtime prompt");
        String prompt = prepared.getPrompt();
        String workspaceRoot = context.getWorkingDir();
        RuntimeSelection runtimeSelection = runtimeSelection(requiredRun, context);
        if (modeSnapshot != null) {
            // 模式覆盖运行时 profile 的 model/effort 默认（其余 profile 绑定保持）
            runtimeSelection = new RuntimeSelection(
                    runtimeSelection.getProfileId(), runtimeSelection.getAgentType(),
                    runtimeSelection.getEndpoint(),
                    modeSnapshot.getModel() != null ? modeSnapshot.getModel()
                            : runtimeSelection.getModel(),
                    modeSnapshot.getEffort() != null ? modeSnapshot.getEffort()
                            : runtimeSelection.getReasoningEffort(),
                    runtimeSelection.getRuntimeEnvironment(),
                    runtimeSelection.getRuntimeVersionPolicy());
        }
        boolean nativeRuntime = context.getAgentType() == AgentType.NATIVE;
        PromptPayload payload = nativeRuntime
                ? new PromptPayload(context.getMessage(),
                CanonicalHashing.sha256(context.getMessage()), HistoryDelivery.TYPED,
                context.getHistory().stream().map(message -> new AgentHistoryMessage(
                        message.getRole(), message.getContent())).collect(Collectors.toList()))
                : new PromptPayload(
                prompt, CanonicalHashing.sha256(prompt), HistoryDelivery.PROMPT_PREFIX);
        return new AgentExecutionPlan(
                new ExecutionIdentity(
                        requiredRun.getId().getValue(), context.getUserId(),
                        "chat:" + requiredRun.getSessionId(), context.getSessionId(),
                        context.getUserMessageId()), runtimeSelection,
                payload,
                new WorkspaceLayout(
                        workspaceRoot, Collections.singletonList(workspaceRoot),
                        Collections.singletonList(workspaceRoot),
                        SandboxMode.WORKSPACE_WRITE),
                binding, runtimeLimits, Collections.emptyList(), context.getResumeId(),
                modeDelivery);
    }

    private String readHandoffContent(ChatRunExecutionContext context) {
        if (handoffFilePort == null || context.getHandoffFilePath() == null) {
            return null;
        }
        return handoffFilePort.readContent(
                context.getWorkingDir(), context.getHandoffFilePath());
    }

    /** SELECTED_CAPABILITIES part：宣告模式能力及其 plugin 命名空间用法。 */
    private String capabilityAnnouncement(
            com.example.agentweb.domain.mode.ModeSnapshot snapshot,
            com.example.agentweb.domain.mode.ResolvedModeCapabilities resolved) {
        StringBuilder announcement = new StringBuilder();
        announcement.append("当前会话绑定了模式「")
                .append(snapshot.getDisplayName()).append("」。\n");
        if (!resolved.getCommands().isEmpty()) {
            announcement.append("可用斜杠命令（以 agent-mode 命名空间注册，直接调用即可）:\n");
            for (com.example.agentweb.domain.capability.CommandDefinition command
                    : resolved.getCommands()) {
                announcement.append("- /agent-mode:").append(command.getIdentifier())
                        .append(": ").append(command.getDescription()).append('\n');
            }
        }
        if (!snapshot.getSkills().isEmpty()) {
            announcement.append("可用 Skills（agent-mode 插件已加载）: ");
            announcement.append(snapshot.getSkills().stream()
                    .map(com.example.agentweb.domain.mode.ModeCapabilities.SkillRef::getIdentifier)
                    .collect(Collectors.joining(", "))).append('\n');
        }
        if (!snapshot.getMcpServers().isEmpty()) {
            announcement.append("可用 MCP Servers: ").append(snapshot.getMcpServers().stream()
                    .map(com.example.agentweb.domain.mode.ModeCapabilities.McpServerRef::getIdentifier)
                    .collect(Collectors.joining(", "))).append('\n');
        }
        return announcement.toString();
    }

    private void requireSupportedContext(
            ChatRun run, ChatRunExecutionContext context) {
        boolean profilesConfigured = profilesConfigured(context.getAgentType());
        if (!profilesConfigured && context.getAgentType() != AgentType.CODEX
                && context.getAgentType() != AgentType.CLAUDE) {
            throw new IllegalStateException(
                    "common Chat Runtime currently supports Codex and Claude CLI");
        }
        if (!profilesConfigured && run.isRecallEnabled()) {
            throw new IllegalStateException(
                    "common Chat Runtime does not yet support recall");
        }
        if (!profilesConfigured && context.getResumeId() != null
                && !context.getResumeId().trim().isEmpty()) {
            throw new IllegalStateException(
                    "common Chat Runtime does not yet support provider resume");
        }
    }

    private RuntimeSelection runtimeSelection(ChatRun run,
                                              ChatRunExecutionContext context) {
        if (selectionStore != null) {
            java.util.Optional<RuntimeSelection> persisted = selectionStore.find(run.getId());
            if (persisted.isPresent()) {
                return persisted.get();
            }
        }
        if (!profilesConfigured(context.getAgentType())) {
            return new RuntimeSelection(context.getAgentType(), RuntimeVersionPolicy.configured());
        }
        RuntimeSelection selected = profileSelector.selection(context.getAgentType(),
                AgentRuntimeSurface.CHAT, RunMode.DISCUSS_READ_ONLY, null, null, null);
        if (selected.getRuntimeEnvironment() != null || context.getEnv() == null
                || context.getEnv().isBlank()) {
            return selected;
        }
        return new RuntimeSelection(selected.getProfileId(), selected.getAgentType(),
                selected.getEndpoint(), selected.getModel(), selected.getReasoningEffort(),
                context.getEnv(), selected.getRuntimeVersionPolicy());
    }

    private boolean profilesConfigured(AgentType agentType) {
        return profileSelector != null && profileSelector.hasProfiles(agentType);
    }
}
