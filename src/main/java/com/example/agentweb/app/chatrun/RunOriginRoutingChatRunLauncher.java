package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.RunOrigin;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 只按 ChatRun 已持久化来源事实选择唯一执行路径的 launcher 路由。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RunOriginRoutingChatRunLauncher
        implements ChatRunLauncher {

    private final ChatRunRepository runRepository;
    private final Map<RunOrigin, ChatRunLauncher> routes;

    public RunOriginRoutingChatRunLauncher(
            ChatRunRepository runRepository,
            Map<RunOrigin, ChatRunLauncher> routes) {
        this.runRepository = Objects.requireNonNull(
                runRepository, "runRepository");
        if (routes == null || routes.containsValue(null)) {
            throw new IllegalArgumentException(
                    "ChatRun launcher routes must not be null or contain null");
        }
        EnumMap<RunOrigin, ChatRunLauncher> exact =
                new EnumMap<RunOrigin, ChatRunLauncher>(RunOrigin.class);
        exact.putAll(routes);
        for (RunOrigin origin : RunOrigin.values()) {
            if (!exact.containsKey(origin)) {
                throw new IllegalStateException(
                        "ChatRun origin " + origin.name()
                                + " must have exactly one launcher route");
            }
        }
        if (exact.size() != RunOrigin.values().length) {
            throw new IllegalStateException(
                    "ChatRun launcher routes contain unsupported origins");
        }
        this.routes = exact;
    }

    @Override
    public void launch(ChatRunId runId) {
        ChatRunId requiredRunId = Objects.requireNonNull(runId, "runId");
        ChatRun run = runRepository.findById(requiredRunId)
                .orElseThrow(() -> new ChatRunNotFoundException(
                        requiredRunId.getValue()));
        routes.get(run.getRunOrigin()).launch(requiredRunId);
    }
}
