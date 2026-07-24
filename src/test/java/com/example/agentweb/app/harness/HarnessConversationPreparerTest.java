package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段对话事务准备组件的聚合持久化编排测试。
 *
 * @author alex
 * @since 2026-07-24
 */
@ExtendWith(MockitoExtension.class)
class HarnessConversationPreparerTest {

    private static final Instant NOW = Instant.parse("2026-07-24T09:00:00Z");

    @Mock
    private HarnessRunRepository repository;
    @Mock
    private ArtifactStore artifactStore;
    @Mock
    private WorkspaceBaselineGateway baselineGateway;

    private HarnessConversationPreparer preparer;
    private HarnessRun run;

    @BeforeEach
    void setUp() {
        UserContext context = () -> Optional.of(new LoginUser("admin", "Admin", null));
        preparer = new HarnessConversationPreparer(repository, artifactStore, baselineGateway,
                new CurrentUserProvider(context), Clock.fixed(NOW, ZoneOffset.UTC));
        run = HarnessRun.create("run-1", "M4", "/workspace", "CODEX", "local",
                "harness@1", "admin", "create-1", StageContract.mvpDefaults(),
                NOW.minusSeconds(10));
        when(repository.findById("run-1")).thenReturn(Optional.of(run));
        when(baselineGateway.capture("/workspace")).thenReturn(WorkspaceBaseline.capture(
                "/workspace", "master", "0123456789012345678901234567890123456789",
                true, String.join("", Collections.nCopies(64, "0")), NOW));
    }

    @Test
    void prepare_should_persist_message_and_opened_attempt_once() {
        StartHarnessConversationCommand command = new StartHarnessConversationCommand(
                "run-1", HarnessStage.ANALYSIS, "message-1", "梳理验收标准");

        PreparedHarnessConversation first = preparer.prepare(command);
        PreparedHarnessConversation duplicate = preparer.prepare(command);

        assertEquals(1, first.getAttemptNumber());
        assertFalse(first.isDuplicated());
        assertTrue(duplicate.isDuplicated());
        assertEquals(1L, run.getEvents().stream()
                .filter(event -> "STAGE_CONVERSATION_MESSAGE".equals(event.getEventType()))
                .count());
        verify(repository, times(1)).update(run);
        verify(baselineGateway, times(1)).capture("/workspace");
        verify(artifactStore, never()).store(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
