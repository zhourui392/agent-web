package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatRun 来源到唯一 launcher 的路由测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RunOriginRoutingChatRunLauncherTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T00:00:00Z");

    private ChatRunRepository runRepository;
    private ChatRunLauncher legacyLauncher;
    private ChatRunLauncher commonRuntimeLauncher;

    @BeforeEach
    void setUp() {
        runRepository = mock(ChatRunRepository.class);
        legacyLauncher = mock(ChatRunLauncher.class);
        commonRuntimeLauncher = mock(ChatRunLauncher.class);
    }

    @Test
    void ordinaryChatIncludingRecallShouldStayOnLegacyRoute() {
        ChatRun run = ChatRun.submit(
                ChatRunId.of("chat-recall"), "session-1", 1L,
                "chat-key", true, NOW);
        when(runRepository.findById(run.getId()))
                .thenReturn(Optional.of(run));
        RunOriginRoutingChatRunLauncher router = router(
                legacyLauncher, commonRuntimeLauncher);

        router.launch(run.getId());

        verify(legacyLauncher).launch(run.getId());
        verify(commonRuntimeLauncher, never()).launch(run.getId());
    }

    @Test
    void workbenchRunShouldUseOnlyCommonRuntimeRoute() {
        ChatRun run = workbenchRun();
        when(runRepository.findById(run.getId()))
                .thenReturn(Optional.of(run));
        RunOriginRoutingChatRunLauncher router = router(
                legacyLauncher, commonRuntimeLauncher);

        router.launch(run.getId());

        verify(commonRuntimeLauncher).launch(run.getId());
        verify(legacyLauncher, never()).launch(run.getId());
    }

    @Test
    void disabledWorkbenchRouteShouldFailWithoutFallingBackToLegacy() {
        ChatRun run = workbenchRun();
        when(runRepository.findById(run.getId()))
                .thenReturn(Optional.of(run));
        ChatRunLauncher disabled = runId -> {
            throw new IllegalStateException(
                    "Workbench common Runtime is disabled");
        };
        RunOriginRoutingChatRunLauncher router = router(
                legacyLauncher, disabled);

        assertThrows(IllegalStateException.class,
                () -> router.launch(run.getId()));

        verify(legacyLauncher, never()).launch(run.getId());
        verify(commonRuntimeLauncher, never()).launch(run.getId());
    }

    @Test
    void missingOrIncompleteOriginRoutesShouldFailAtConstruction() {
        assertThrows(IllegalStateException.class,
                () -> new RunOriginRoutingChatRunLauncher(
                        runRepository, new EnumMap<RunOrigin, ChatRunLauncher>(
                        RunOrigin.class)));
        Map<RunOrigin, ChatRunLauncher> incomplete =
                new EnumMap<RunOrigin, ChatRunLauncher>(RunOrigin.class);
        incomplete.put(RunOrigin.CHAT, legacyLauncher);
        assertThrows(IllegalStateException.class,
                () -> new RunOriginRoutingChatRunLauncher(
                        runRepository, incomplete));
    }

    private RunOriginRoutingChatRunLauncher router(
            ChatRunLauncher chatLauncher,
            ChatRunLauncher workbenchLauncher) {
        Map<RunOrigin, ChatRunLauncher> routes =
                new EnumMap<RunOrigin, ChatRunLauncher>(RunOrigin.class);
        routes.put(RunOrigin.CHAT, chatLauncher);
        routes.put(RunOrigin.WORKBENCH, workbenchLauncher);
        return new RunOriginRoutingChatRunLauncher(runRepository, routes);
    }

    private ChatRun workbenchRun() {
        return ChatRun.submit(
                ChatRunId.of("workbench-run"), "session-1", 1L,
                "workbench-key", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        "workbench-1:IMPLEMENT_TEST", "workbench-run"), NOW);
    }
}
